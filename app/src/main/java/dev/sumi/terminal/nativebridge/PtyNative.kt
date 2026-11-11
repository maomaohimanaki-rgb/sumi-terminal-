package dev.sumi.terminal.nativebridge

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import com.termux.terminal.JNI
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Native PTY bridge backed by `libtermux.so` (`com.termux.terminal.JNI`) which opens
 * `/dev/ptmx`, unlocks `/dev/pts/N`, creates a new session (`setsid()`), attaches the
 * controlling terminal, configures `termios` (`IUTF8`, `ICRNL`, `ONLCR`, `ECHO`, `ISIG`),
 * and executes the target binary (`libproot.so`).
 */
object PtyNative {

    private val nextSessionHandle = AtomicLong(1_000L)
    private val nativePtySessions = ConcurrentHashMap<Long, NativeTermuxPtySession>()
    private val processSessions = ConcurrentHashMap<Long, RealProcessPtySession>()

    fun startSession(
        cmd: String,
        args: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        listener: PtyListener
    ): Long {
        val workingDir = File(cwd).apply { if (!exists()) mkdirs() }

        if (JNI.isLoaded) {
            val session = runCatching {
                val pidArray = IntArray(1)
                val execArgs = if (args.isNotEmpty()) {
                    args.clone().also { it[0] = cmd }
                } else {
                    arrayOf(cmd)
                }
                val ptmFd = JNI.createSubprocess(
                    cmd,
                    workingDir.absolutePath,
                    execArgs,
                    env,
                    pidArray,
                    rows,
                    cols
                )
                if (ptmFd >= 0 && pidArray[0] > 0) {
                    runCatching { JNI.setPtyUTF8Mode(ptmFd) }
                    NativeTermuxPtySession(
                        ptmFd = ptmFd,
                        childPid = pidArray[0],
                        listener = listener
                    )
                } else {
                    null
                }
            }.getOrNull()

            if (session != null) {
                val handle = nextSessionHandle.incrementAndGet()
                nativePtySessions[handle] = session
                session.start()
                return handle
            }
        }

        val handle = nextSessionHandle.incrementAndGet()
        val fallbackSession = RealProcessPtySession(
            cmd = cmd,
            args = args,
            env = env,
            cwd = workingDir.absolutePath,
            rows = rows,
            cols = cols,
            listener = listener
        )
        processSessions[handle] = fallbackSession
        fallbackSession.start()
        return handle
    }

    fun writeSession(handle: Long, data: ByteArray): Int {
        nativePtySessions[handle]?.let { return it.write(data) }
        return processSessions[handle]?.write(data) ?: -1
    }

    fun resizeSession(handle: Long, rows: Int, cols: Int) {
        nativePtySessions[handle]?.let {
            it.resize(rows, cols)
            return
        }
        processSessions[handle]?.resize(rows, cols)
    }

    fun killSession(handle: Long, sig: Int) {
        nativePtySessions.remove(handle)?.let {
            it.kill(sig)
            return
        }
        processSessions.remove(handle)?.kill(sig)
    }

    fun readerTid(handle: Long): Int {
        nativePtySessions[handle]?.let { return it.readerTid.get() }
        return processSessions[handle]?.readerTid?.get() ?: 0
    }

    private fun wrapRawFd(fd: Int): FileDescriptor {
        val fdObj = FileDescriptor()
        val setReflectively = runCatching {
            val field = try {
                FileDescriptor::class.java.getDeclaredField("descriptor")
            } catch (_: NoSuchFieldException) {
                FileDescriptor::class.java.getDeclaredField("fd")
            }
            field.isAccessible = true
            field.set(fdObj, fd)
            true
        }.getOrDefault(false)

        if (!setReflectively) {
            return ParcelFileDescriptor.adoptFd(fd).fileDescriptor
        }
        return fdObj
    }

    private class NativeTermuxPtySession(
        private val ptmFd: Int,
        private val childPid: Int,
        private val listener: PtyListener
    ) {
        val readerTid = AtomicInteger(0)
        private val running = AtomicBoolean(true)
        private val fdObj: FileDescriptor = wrapRawFd(ptmFd)
        private val inputStream = FileInputStream(fdObj)
        private val outputStream = FileOutputStream(fdObj)

        fun start() {
            thread(name = "pty-reader-$childPid", isDaemon = true) {
                val tid = Process.myTid()
                readerTid.set(tid)
                listener.onReaderStarted(tid)

                val buf = ByteArray(16 * 1024)
                try {
                    while (running.get()) {
                        val n = inputStream.read(buf)
                        if (n > 0) {
                            listener.onOutput(buf.copyOfRange(0, n))
                        } else if (n < 0) {
                            break
                        }
                    }
                } catch (_: Throwable) {
                    // Master PTY returns EIO when all slave descriptors close
                } finally {
                    val exitCode = runCatching { JNI.waitFor(childPid) }.getOrDefault(0)
                    if (running.compareAndSet(true, false)) {
                        runCatching { JNI.close(ptmFd) }
                    }
                    listener.onExit(exitCode)
                }
            }
        }

        fun write(data: ByteArray): Int {
            if (!running.get() || data.isEmpty()) return -1
            return runCatching {
                synchronized(outputStream) {
                    outputStream.write(data)
                    outputStream.flush()
                }
                data.size
            }.getOrDefault(-1)
        }

        fun resize(rows: Int, cols: Int) {
            if (!running.get()) return
            runCatching {
                JNI.setPtyWindowSize(ptmFd, rows, cols)
            }
        }

        fun kill(sig: Int) {
            if (running.compareAndSet(true, false)) {
                runCatching { Os.kill(-childPid, sig) }
                runCatching { Os.kill(childPid, sig) }
                runCatching { JNI.close(ptmFd) }
            }
        }
    }

    private class RealProcessPtySession(
        private val cmd: String,
        private val args: Array<String>,
        private val env: Array<String>,
        private val cwd: String,
        @Volatile var rows: Int,
        @Volatile var cols: Int,
        private val listener: PtyListener
    ) {
        val readerTid = AtomicInteger(0)
        private val running = AtomicBoolean(true)
        @Volatile
        private var process: java.lang.Process? = null
        @Volatile
        private var stdinStream: OutputStream? = null

        fun start() {
            thread(name = "pty-fallback-reader", isDaemon = true) {
                val tid = Process.myTid()
                readerTid.set(tid)
                listener.onReaderStarted(tid)

                val proc = spawnRealProcess()
                if (proc == null) {
                    running.set(false)
                    listener.onExit(127)
                    return@thread
                }

                process = proc
                stdinStream = proc.outputStream
                val input = proc.inputStream
                val buf = ByteArray(16 * 1024)

                try {
                    while (running.get()) {
                        val n = input.read(buf)
                        if (n > 0) {
                            listener.onOutput(buf.copyOfRange(0, n))
                        } else if (n < 0) {
                            break
                        }
                    }
                } catch (_: Throwable) {
                    // Process exited
                } finally {
                    val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                    running.set(false)
                    listener.onExit(code)
                }
            }
        }

        private fun spawnRealProcess(): java.lang.Process? {
            val workingDir = File(cwd).apply { if (!exists()) mkdirs() }
            val envMap = mutableMapOf<String, String>()
            for (entry in env) {
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    envMap[entry.substring(0, eq)] = entry.substring(eq + 1)
                }
            }
            envMap["LINES"] = rows.toString()
            envMap["COLUMNS"] = cols.toString()

            val commandList = if (args.isNotEmpty()) {
                val list = args.toMutableList()
                list[0] = cmd
                if (list.lastOrNull() == "--login" || list.lastOrNull() == "-l") {
                    list.add("-i")
                }
                list
            } else {
                listOf(cmd)
            }

            val primaryAttempt = runCatching {
                val pb = ProcessBuilder(commandList)
                pb.directory(workingDir)
                pb.environment().putAll(envMap)
                pb.redirectErrorStream(true)
                pb.start()
            }.getOrNull()

            if (primaryAttempt != null) {
                return primaryAttempt
            }

            return runCatching {
                val fallbackPb = ProcessBuilder("/system/bin/sh", "-i")
                fallbackPb.directory(workingDir)
                fallbackPb.environment().putAll(envMap)
                fallbackPb.redirectErrorStream(true)
                fallbackPb.start()
            }.getOrNull()
        }

        fun write(data: ByteArray): Int {
            val out = stdinStream ?: return -1
            return runCatching {
                synchronized(out) {
                    val translated = ByteArray(data.size) { idx ->
                        if (data[idx] == '\r'.code.toByte()) '\n'.code.toByte() else data[idx]
                    }
                    out.write(translated)
                    out.flush()
                }
                data.size
            }.getOrDefault(-1)
        }

        fun resize(newRows: Int, newCols: Int) {
            rows = newRows
            cols = newCols
        }

        fun kill(sig: Int) {
            if (running.compareAndSet(true, false)) {
                runCatching { process?.destroyForcibly() }
            }
        }
    }
}
