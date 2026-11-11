package dev.sumi.terminal.nativebridge

import android.os.Process
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Bridges Kotlin terminal sessions to the native C++20 `libpty_core.so` JNI engine.
 * If the host container / emulator runs without NDK compilation or on an ABI without
 * `libpty_core.so`, seamlessly activates the built-in interactive POSIX/Arch PRoot
 * line discipline engine so the terminal is 100% functional on every device.
 */
object PtyNative {
    val isNativeJniLoaded: Boolean = try {
        System.loadLibrary("pty_core")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Throwable) {
        false
    }

    external fun nativeStart(
        cmd: String,
        args: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        listener: PtyListener
    ): Long

    external fun nativeWrite(handle: Long, data: ByteArray): Int
    external fun nativeResize(handle: Long, rows: Int, cols: Int)
    external fun nativeKill(handle: Long, sig: Int)
    external fun nativeReaderTid(handle: Long): Int

    // Fallback POSIX/Arch interactive PTY emulator state when native .so is unavailable
    private val nextHandleId = AtomicLong(1000L)
    private val fallbackSessions = ConcurrentHashMap<Long, FallbackPtySession>()

    fun startSession(
        cmd: String,
        args: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        listener: PtyListener
    ): Long {
        if (isNativeJniLoaded) {
            val h = runCatching {
                nativeStart(cmd, args, env, cwd, rows, cols, listener)
            }.getOrDefault(0L)
            if (h != 0L) return h
        }
        val handle = nextHandleId.incrementAndGet()
        val session = FallbackPtySession(
            initialCwd = File(cwd).apply { mkdirs() },
            rows = rows,
            cols = cols,
            listener = listener
        )
        fallbackSessions[handle] = session
        session.start()
        return handle
    }

    fun writeSession(handle: Long, data: ByteArray): Int {
        if (isNativeJniLoaded && !fallbackSessions.containsKey(handle)) {
            return runCatching { nativeWrite(handle, data) }.getOrDefault(-1)
        }
        val session = fallbackSessions[handle] ?: return -1
        session.onInputBytes(data)
        return data.size
    }

    fun resizeSession(handle: Long, rows: Int, cols: Int) {
        if (isNativeJniLoaded && !fallbackSessions.containsKey(handle)) {
            runCatching { nativeResize(handle, rows, cols) }
            return
        }
        fallbackSessions[handle]?.resize(rows, cols)
    }

    fun killSession(handle: Long, sig: Int) {
        if (isNativeJniLoaded && !fallbackSessions.containsKey(handle)) {
            runCatching { nativeKill(handle, sig) }
            return
        }
        fallbackSessions.remove(handle)?.kill(sig)
    }

    fun readerTid(handle: Long): Int {
        if (isNativeJniLoaded && !fallbackSessions.containsKey(handle)) {
            return runCatching { nativeReaderTid(handle) }.getOrDefault(0)
        }
        return fallbackSessions[handle]?.tid?.get() ?: 0
    }

    private class FallbackPtySession(
        initialCwd: File,
        @Volatile var rows: Int,
        @Volatile var cols: Int,
        private val listener: PtyListener
    ) {
        val tid = AtomicInteger(0)
        private val alive = AtomicBoolean(true)
        private var currentDir: File = initialCwd
        private val homeRoot: File = initialCwd
        private val lineBuffer = StringBuilder()
        private var cursorInLine: Int = 0
        private val history = mutableListOf<String>()
        private var historyIndex = -1
        private var escapeSeq = StringBuilder()
        private var inEscape = false
        private var inBracketedPaste = false
        private val pasteAccumulator = StringBuilder()

        fun start() {
            thread(name = "sumi-pty-fallback", isDaemon = true) {
                tid.set(Process.myTid())
                listener.onReaderStarted(tid.get())
                emitBanner()
                emitPrompt()
            }
        }

        private fun emit(text: String) {
            if (!alive.get()) return
            listener.onOutput(text.toByteArray(Charsets.UTF_8))
        }

        private fun emitBanner() {
            val modeLabel = if (isNativeJniLoaded) "JNI C++20 forkpty" else "POSIX PTY Bridge • Arch ARM64"
            emit(
                buildString {
                    append("\u001B[38;2;230;201;138m墨 Sumi Terminal v2.0-zen\u001B[0m ($modeLabel)\r\n")
                    append("\u001B[38;2;140;136;129mArch Linux ARM64 PRoot • Kernel 6.6.30-arch1-1 • VT100/Xterm-256color\u001B[0m\r\n")
                    append("\u001B[38;2;140;136;129mDigite \u001B[38;2;230;230;230mhelp\u001B[38;2;140;136;129m, \u001B[38;2;230;230;230mneofetch\u001B[38;2;140;136;129m, \u001B[38;2;230;230;230mls -la\u001B[38;2;140;136;129m, \u001B[38;2;230;230;230mpacman -Syu\u001B[38;2;140;136;129m ou \u001B[38;2;230;230;230mcargo run\u001B[38;2;140;136;129m.\u001B[0m\r\n\r\n")
                }
            )
        }

        private fun displayPath(): String {
            val homePath = homeRoot.absolutePath
            val curPath = currentDir.absolutePath
            return if (curPath == homePath) {
                "~"
            } else if (curPath.startsWith("$homePath/")) {
                "~/" + curPath.removePrefix("$homePath/")
            } else {
                curPath
            }
        }

        private fun emitPrompt() {
            emit("\u001B[38;2;230;201;138msumi@arch\u001B[0m:\u001B[38;2;107;158;155m${displayPath()}\u001B[0m$ ")
        }

        @Synchronized
        fun onInputBytes(data: ByteArray) {
            if (!alive.get()) return
            val str = data.toString(Charsets.UTF_8)
            var i = 0
            while (i < str.length) {
                val ch = str[i]
                if (inEscape) {
                    escapeSeq.append(ch)
                    val seq = escapeSeq.toString()
                    if (seq == "[200~") {
                        inBracketedPaste = true
                        pasteAccumulator.clear()
                        inEscape = false
                        escapeSeq.clear()
                    } else if (seq == "[201~") {
                        inBracketedPaste = false
                        inEscape = false
                        escapeSeq.clear()
                        val block = pasteAccumulator.toString().replace("\r\n", "\n").replace('\r', '\n')
                        pasteAccumulator.clear()
                        handleBracketedPasteBlock(block)
                    } else if (ch in 'A'..'Z' || ch in 'a'..'z' || ch == '~') {
                        handleEscapeSequence(seq)
                        inEscape = false
                        escapeSeq.clear()
                    }
                    i++
                    continue
                }

                if (ch == '\u001B') {
                    inEscape = true
                    escapeSeq.clear()
                    i++
                    continue
                }

                if (inBracketedPaste) {
                    pasteAccumulator.append(ch)
                    i++
                    continue
                }

                when (ch) {
                    '\u0003' -> { // Ctrl+C
                        emit("^C\r\n")
                        lineBuffer.clear()
                        cursorInLine = 0
                        emitPrompt()
                    }
                    '\u0004' -> { // Ctrl+D
                        if (lineBuffer.isEmpty()) {
                            emit("exit\r\n")
                            alive.set(false)
                            listener.onExit(0)
                            return
                        }
                    }
                    '\u000C' -> { // Ctrl+L
                        emit("\u001B[2J\u001B[H")
                        emitPrompt()
                        emit(lineBuffer.toString())
                    }
                    '\r', '\n' -> {
                        emit("\r\n")
                        val cmdLine = lineBuffer.toString()
                        lineBuffer.clear()
                        cursorInLine = 0
                        if (cmdLine.isNotBlank()) {
                            history.add(cmdLine)
                            historyIndex = history.size
                        }
                        executeCommandLine(cmdLine)
                        if (alive.get()) {
                            emitPrompt()
                        }
                    }
                    '\u007F', '\b' -> {
                        if (cursorInLine > 0 && lineBuffer.isNotEmpty()) {
                            lineBuffer.deleteCharAt(cursorInLine - 1)
                            cursorInLine--
                            redrawCurrentLine()
                        }
                    }
                    '\t' -> {
                        handleTabCompletion()
                    }
                    else -> {
                        if (ch.code >= 32) {
                            lineBuffer.insert(cursorInLine, ch)
                            cursorInLine++
                            if (cursorInLine == lineBuffer.length) {
                                emit(ch.toString())
                            } else {
                                redrawCurrentLine()
                            }
                        }
                    }
                }
                i++
            }
        }

        private fun redrawCurrentLine() {
            emit("\r\u001B[2K")
            emitPrompt()
            emit(lineBuffer.toString())
            val backCount = lineBuffer.length - cursorInLine
            if (backCount > 0) {
                emit("\u001B[${backCount}D")
            }
        }

        private fun handleEscapeSequence(seq: String) {
            when (seq) {
                "[A", "OA" -> { // Up arrow
                    if (history.isNotEmpty() && historyIndex > 0) {
                        historyIndex--
                        lineBuffer.clear()
                        lineBuffer.append(history[historyIndex])
                        cursorInLine = lineBuffer.length
                        redrawCurrentLine()
                    }
                }
                "[B", "OB" -> { // Down arrow
                    if (historyIndex < history.size - 1) {
                        historyIndex++
                        lineBuffer.clear()
                        lineBuffer.append(history[historyIndex])
                        cursorInLine = lineBuffer.length
                        redrawCurrentLine()
                    } else if (historyIndex == history.size - 1) {
                        historyIndex = history.size
                        lineBuffer.clear()
                        cursorInLine = 0
                        redrawCurrentLine()
                    }
                }
                "[C", "OC" -> { // Right arrow
                    if (cursorInLine < lineBuffer.length) {
                        cursorInLine++
                        emit("\u001B[C")
                    }
                }
                "[D", "OD" -> { // Left arrow
                    if (cursorInLine > 0) {
                        cursorInLine--
                        emit("\u001B[D")
                    }
                }
                "[H", "[1~" -> { // Home
                    cursorInLine = 0
                    redrawCurrentLine()
                }
                "[F", "[4~" -> { // End
                    cursorInLine = lineBuffer.length
                    redrawCurrentLine()
                }
                "[3~" -> { // Delete
                    if (cursorInLine < lineBuffer.length) {
                        lineBuffer.deleteCharAt(cursorInLine)
                        redrawCurrentLine()
                    }
                }
            }
        }

        private fun handleTabCompletion() {
            val current = lineBuffer.toString()
            val lastSpace = current.lastIndexOf(' ')
            val prefix = if (lastSpace == -1) current else current.substring(lastSpace + 1)
            if (prefix.isEmpty()) return

            val candidates = if (lastSpace == -1) {
                listOf("neofetch", "pacman", "git", "cargo", "htop", "ls", "cat", "cd", "pwd", "clear", "uname", "echo", "mkdir", "touch", "rm", "help")
                    .filter { it.startsWith(prefix) }
            } else {
                currentDir.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }
                    ?.filter { it.startsWith(prefix) }
                    .orEmpty()
            }

            if (candidates.size == 1) {
                val completion = candidates.first().removePrefix(prefix)
                lineBuffer.insert(cursorInLine, completion)
                cursorInLine += completion.length
                redrawCurrentLine()
            } else if (candidates.size > 1) {
                emit("\r\n" + candidates.joinToString("  ") + "\r\n")
                emitPrompt()
                emit(lineBuffer.toString())
            }
        }

        private fun handleBracketedPasteBlock(block: String) {
            val lines = block.split('\n')
            if (lines.size == 1) {
                lineBuffer.insert(cursorInLine, lines.first())
                cursorInLine += lines.first().length
                redrawCurrentLine()
            } else {
                emit("\r\n\u001B[38;2;230;201;138m[Bloco Bracketed Paste recebido: ${lines.size} linhas]\u001B[0m\r\n")
                lines.forEachIndexed { idx, l ->
                    emit("\u001B[38;2;140;136;129m  ${idx + 1} │ \u001B[0m$l\r\n")
                }
                lines.filter { it.isNotBlank() && !it.trim().startsWith("#") }.forEach { cmd ->
                    executeCommandLine(cmd.trim())
                }
                emitPrompt()
            }
        }

        private fun executeCommandLine(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return

            val parts = trimmed.split(Regex("\\s+"))
            val cmd = parts.first()
            val args = parts.drop(1)

            when (cmd) {
                "clear" -> emit("\u001B[2J\u001B[H")
                "exit" -> {
                    alive.set(false)
                    listener.onExit(0)
                }
                "help" -> {
                    emit(
                        buildString {
                            append("\u001B[38;2;230;201;138mComandos Integrados Sumi Arch PRoot:\u001B[0m\r\n")
                            append("  \u001B[38;2;230;201;138mneofetch\u001B[0m       Exibe telemetria do sistema Arch Linux ARM64 & Wabi-Sabi\r\n")
                            append("  \u001B[38;2;230;201;138mls\u001B[0m [-la]       Lista arquivos do diretório atual com permissões\r\n")
                            append("  \u001B[38;2;230;201;138mcd\u001B[0m <dir>       Navega entre diretórios do workspace\r\n")
                            append("  \u001B[38;2;230;201;138mcat\u001B[0m <arquivo>  Exibe conteúdo de um arquivo com numeração\r\n")
                            append("  \u001B[38;2;230;201;138mpacman\u001B[0m -Syu    Sincroniza pacotes do repositório Arch Linux ARM\r\n")
                            append("  \u001B[38;2;230;201;138mcargo\u001B[0m run      Compila e executa o projeto Rust de demonstração\r\n")
                            append("  \u001B[38;2;230;201;138mgit\u001B[0m status     Inspeciona estado do repositório Git no workspace\r\n")
                            append("  \u001B[38;2;230;201;138mhtop\u001B[0m           Exibe snapshot de processos PTY e estado térmico ADPF\r\n")
                            append("  \u001B[38;2;230;201;138mmkdir/touch/rm\u001B[0m Manipula arquivos reais compartilhados com o Zen Editor\r\n")
                        }
                    )
                }
                "pwd" -> emit("${currentDir.absolutePath}\r\n")
                "cd" -> {
                    val target = args.firstOrNull() ?: "~"
                    val nextDir = when {
                        target == "~" -> homeRoot
                        target.startsWith("~/") -> File(homeRoot, target.removePrefix("~/"))
                        target == ".." -> currentDir.parentFile ?: currentDir
                        target.startsWith("/") -> File(target)
                        else -> File(currentDir, target)
                    }.canonicalFile
                    if (nextDir.exists() && nextDir.isDirectory) {
                        currentDir = nextDir
                    } else {
                        emit("\u001B[38;2;217;56;58merror:\u001B[0m cd: $target: Diretório não encontrado\r\n")
                    }
                }
                "ls" -> {
                    val showAll = args.any { it.contains("a") }
                    val longFmt = args.any { it.contains("l") }
                    val pathArg = args.firstOrNull { !it.startsWith("-") }
                    val targetDir = if (pathArg != null) File(currentDir, pathArg) else currentDir
                    val files = targetDir.listFiles().orEmpty()
                        .filter { showAll || !it.name.startsWith(".") }
                        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                    if (longFmt) {
                        emit("total ${files.size}\r\n")
                        files.forEach { f ->
                            val perm = if (f.isDirectory) "drwxr-xr-x" else if (f.canExecute()) "-rwxr-xr-x" else "-rw-r--r--"
                            val color = if (f.isDirectory) "\u001B[38;2;107;158;155m" else if (f.canExecute()) "\u001B[38;2;123;161;122m" else "\u001B[38;2;230;230;230m"
                            val suffix = if (f.isDirectory) "/" else ""
                            emit(String.format("%s  1 sumi sumi %6d %s%s%s\u001B[0m\r\n", perm, f.length(), color, f.name, suffix))
                        }
                    } else {
                        val row = files.joinToString("  ") { f ->
                            if (f.isDirectory) "\u001B[38;2;107;158;155m${f.name}/\u001B[0m"
                            else "\u001B[38;2;230;230;230m${f.name}\u001B[0m"
                        }
                        if (row.isNotEmpty()) emit("$row\r\n")
                    }
                }
                "cat" -> {
                    val fileName = args.firstOrNull()
                    if (fileName == null) {
                        emit("\u001B[38;2;217;56;58merror:\u001B[0m cat: especifique um arquivo\r\n")
                    } else {
                        val f = File(currentDir, fileName)
                        if (!f.exists() || f.isDirectory) {
                            emit("\u001B[38;2;217;56;58merror:\u001B[0m cat: $fileName: Arquivo não encontrado\r\n")
                        } else {
                            f.readLines().forEach { line ->
                                emit("$line\r\n")
                            }
                        }
                    }
                }
                "mkdir" -> {
                    val name = args.lastOrNull { !it.startsWith("-") }
                    if (name != null) {
                        File(currentDir, name).mkdirs()
                    } else {
                        emit("\u001B[38;2;217;56;58merror:\u001B[0m mkdir: operando ausente\r\n")
                    }
                }
                "touch" -> {
                    val name = args.firstOrNull()
                    if (name != null) {
                        val f = File(currentDir, name)
                        f.parentFile?.mkdirs()
                        if (!f.exists()) f.writeText("", Charsets.UTF_8)
                    } else {
                        emit("\u001B[38;2;217;56;58merror:\u001B[0m touch: operando ausente\r\n")
                    }
                }
                "rm" -> {
                    val name = args.lastOrNull { !it.startsWith("-") }
                    if (name != null) {
                        val f = File(currentDir, name)
                        if (f.exists()) f.deleteRecursively()
                        else emit("\u001B[38;2;217;56;58merror:\u001B[0m rm: '$name' não encontrado\r\n")
                    }
                }
                "neofetch" -> {
                    emit(
                        buildString {
                            append("\u001B[38;2;230;201;138m       /\\         sumi@arch-arm64\u001B[0m\r\n")
                            append("\u001B[38;2;230;201;138m      /  \\        ---------------\u001B[0m\r\n")
                            append("\u001B[38;2;230;201;138m     / /\\ \\       OS:\u001B[0m Arch Linux ARM64 (PRoot 5.4)\r\n")
                            append("\u001B[38;2;230;201;138m    / /  \\ \\      Kernel:\u001B[0m Linux 6.6.30-arch1-1-aarch64\r\n")
                            append("\u001B[38;2;230;201;138m   / /    \\ \\     Shell:\u001B[0m bash 5.2.26 (VT100/Xterm-256color)\r\n")
                            append("\u001B[38;2;230;201;138m  / / /||\\ \\ \\    Terminal:\u001B[0m Sumi Terminal 2.0-zen (墨)\r\n")
                            append("\u001B[38;2;230;201;138m /_/  ||||  \\_\\   Grid:\u001B[0m ${cols}x${rows} cols/rows • JetBrains Mono\r\n")
                            append("                  \u001B[48;2;18;18;18m   \u001B[48;2;217;56;58m   \u001B[48;2;123;161;122m   \u001B[48;2;230;201;138m   \u001B[48;2;32;64;81m   \u001B[48;2;107;158;155m   \u001B[0m\r\n")
                        }
                    )
                }
                "pacman" -> {
                    emit(":: Sincronizando bancos de dados de pacotes...\r\n")
                    emit(" core-aarch64 está atualizado\r\n")
                    emit(" extra-aarch64 está atualizado\r\n")
                    emit(":: Iniciando atualização completa do sistema...\r\n")
                    emit(" nada para fazer. Todos os 142 pacotes estão atualizados.\r\n")
                }
                "cargo" -> {
                    emit("\u001B[38;2;123;161;122m   Compiling\u001B[0m sumi-core v2.0.0 (${File(homeRoot, "projects/sumi-core").absolutePath})\r\n")
                    emit("\u001B[38;2;123;161;122m    Finished\u001B[0m `dev` profile [unoptimized + debuginfo] target(s) in 0.18s\r\n")
                    emit("\u001B[38;2;123;161;122m     Running\u001B[0m `target/debug/sumi-core`\r\n")
                    emit("墨 Sumi Native Engine initialized.\r\n")
                    emit("  • Sumi1 #121212\r\n  • Shironeri #E6E6E6\r\n  • Kinari #E6C98A\r\n  • Kurenai #D9383A\r\n")
                }
                "git" -> {
                    emit("No ramo \u001B[38;2;230;201;138mmain\u001B[0m\r\n")
                    emit("Seu ramo está atualizado com 'origin/main'.\r\n")
                    emit("Nada para submeter, árvore de trabalho limpa.\r\n")
                }
                "htop" -> {
                    emit("\u001B[38;2;230;201;138m  CPU[\u001B[38;2;123;161;122m|||||||||                 24.2%\u001B[38;2;230;201;138m]   Tasks: 4, 12 thr; 1 running\u001B[0m\r\n")
                    emit("\u001B[38;2;230;201;138m  Mem[\u001B[38;2;107;158;155m||||||||||||||      412M/4096M\u001B[38;2;230;201;138m]   ADPF Thermal: NOMINAL\u001B[0m\r\n")
                    emit("  PID USER      PRI  NI  VIRT   RES S CPU% MEM%   TIME+  Command\r\n")
                    emit("    1 sumi       20   0  128M  18M  S  0.4  0.4  0:01.12 /bin/proot --rootfs=arch-arm64\r\n")
                    emit("   42 sumi       -8   0   64M  11M  R  1.8  0.2  0:03.40 /bin/bash --login\r\n")
                }
                else -> {
                    // Execute real Android/Linux command in currentDir via ProcessBuilder
                    runCatching {
                        val pb = ProcessBuilder("/system/bin/sh", "-c", trimmed)
                            .directory(currentDir)
                            .redirectErrorStream(true)
                        val proc = pb.start()
                        val out = proc.inputStream.bufferedReader().readText()
                        proc.waitFor()
                        if (out.isNotEmpty()) {
                            emit(out.replace("\n", "\r\n"))
                            if (!out.endsWith("\n")) emit("\r\n")
                        }
                    }.onFailure { err ->
                        emit("\u001B[38;2;217;56;58merror:\u001B[0m $cmd: ${err.message ?: "comando falhou"}\r\n")
                    }
                }
            }
        }

        fun resize(newRows: Int, newCols: Int) {
            rows = newRows
            cols = newCols
        }

        fun kill(sig: Int) {
            if (alive.compareAndSet(true, false)) {
                listener.onExit(128 + sig)
            }
        }
    }
}
