package dev.sumi.terminal.service

import dev.sumi.terminal.nativebridge.PtyListener
import dev.sumi.terminal.nativebridge.PtyNative
import dev.sumi.terminal.perf.PerformanceManager
import dev.sumi.terminal.terminal.TerminalBuffer
import dev.sumi.terminal.ui.theme.SumiPalettes
import dev.sumi.terminal.ui.theme.WabiSabiPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TerminalSession(
    val id: String,
    val index: Int,
    initialTitle: String,
    private val workingDir: File,
    private val performanceManager: PerformanceManager,
    private val paletteProvider: () -> WabiSabiPalette = { SumiPalettes.Carvao }
) : PtyListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val buffer = TerminalBuffer(initialRows = 36, initialCols = 80)

    private val _title = MutableStateFlow(initialTitle)
    val title: StateFlow<String> = _title.asStateFlow()

    private val _isAlive = MutableStateFlow(true)
    val isAlive: StateFlow<Boolean> = _isAlive.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _renderTick = MutableStateFlow(0L)
    val renderTick: StateFlow<Long> = _renderTick.asStateFlow()

    private var nativeHandle: Long = 0L
    private var resizeDebounceJob: Job? = null

    fun start() {
        if (nativeHandle != 0L) return
        val cmd = "/system/bin/sh"
        val args = arrayOf("sh", "-i")
        val env = arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=${workingDir.absolutePath}",
            "LANG=en_US.UTF-8"
        )
        nativeHandle = PtyNative.startSession(
            cmd = cmd,
            args = args,
            env = env,
            cwd = workingDir.absolutePath,
            rows = buffer.rows,
            cols = buffer.cols,
            listener = this
        )
    }

    fun write(bytes: ByteArray) {
        if (nativeHandle == 0L || !_isAlive.value) return
        PtyNative.writeSession(nativeHandle, bytes)
    }

    /**
     * BugFix #3: Debounces TIOCSWINSZ resize events during keyboard open/close animations
     * (120ms settle window) so bash Readline never corrupts multiline cursor matrices.
     */
    fun requestResizeDebounced(rows: Int, cols: Int) {
        val clampedRows = rows.coerceIn(6, 200)
        val clampedCols = cols.coerceIn(20, 300)
        if (clampedRows == buffer.rows && clampedCols == buffer.cols) return

        resizeDebounceJob?.cancel()
        resizeDebounceJob = scope.launch {
            delay(120L)
            buffer.resize(clampedRows, clampedCols)
            if (nativeHandle != 0L && _isAlive.value) {
                PtyNative.resizeSession(nativeHandle, clampedRows, clampedCols)
            }
            _renderTick.value = System.nanoTime()
        }
    }

    fun rename(newTitle: String) {
        _title.value = newTitle
    }

    fun close() {
        resizeDebounceJob?.cancel()
        if (nativeHandle != 0L) {
            PtyNative.killSession(nativeHandle, 15) // SIGTERM
            nativeHandle = 0L
        }
        _isAlive.value = false
    }

    override fun onOutput(data: ByteArray) {
        buffer.appendBytes(data, paletteProvider())
        _renderTick.value = System.nanoTime()
    }

    override fun onExit(code: Int) {
        _isAlive.value = false
        _exitCode.value = code
        val exitBanner = "\r\n\u001B[38;2;140;136;129m[Processo encerrado com código $code]\u001B[0m\r\n"
        buffer.appendBytes(exitBanner.toByteArray(Charsets.UTF_8), paletteProvider())
        _renderTick.value = System.nanoTime()
    }

    override fun onReaderStarted(tid: Int) {
        performanceManager.boostReaderThread(tid)
    }
}
