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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LaunchConfig(
    val cmd: String,
    val args: Array<String>,
    val env: Array<String>,
    val cwd: String
)

class TerminalSession(
    val id: String,
    val index: Int,
    initialTitle: String,
    private val launchConfig: LaunchConfig,
    private val performanceManager: PerformanceManager,
    private val paletteProvider: () -> WabiSabiPalette = { SumiPalettes.Carvao }
) : PtyListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    val buffer = TerminalBuffer(initialRows = 36, initialCols = 80)

    private val _title = MutableStateFlow(initialTitle)
    val title: StateFlow<String> = _title.asStateFlow()

    private val _isAlive = MutableStateFlow(true)
    val isAlive: StateFlow<Boolean> = _isAlive.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _renderTick = MutableStateFlow(0L)
    val renderTick: StateFlow<Long> = _renderTick.asStateFlow()

    private val _scrollOffset = MutableStateFlow(0)
    val scrollOffset: StateFlow<Int> = _scrollOffset.asStateFlow()

    private var nativeHandle: Long = 0L
    private var readerTid: Int = 0
    private var resizeDebounceJob: Job? = null
    private var lastFrameEmitMs: Long = 0L

    init {
        // Sequential background PTY writer: processes large pastes in 1024-byte UTF-8 safe blocks
        // on Dispatchers.IO so 800+ line pastes never saturate the 4KB kernel PTY buffer or block the UI thread.
        ioWriteScope.launch {
            val chunkSize = 1024
            for (payload in writeChannel) {
                val handle = nativeHandle
                if (handle == 0L || !_isAlive.value || payload.isEmpty()) continue

                if (payload.size <= chunkSize) {
                    PtyNative.writeSession(handle, payload)
                } else {
                    var offset = 0
                    val total = payload.size
                    while (offset < total && _isAlive.value) {
                        var end = (offset + chunkSize).coerceAtMost(total)
                        // Never split in the middle of a multi-byte UTF-8 character sequence (10xxxxxx)
                        while (end < total && (payload[end].toInt() and 0xC0) == 0x80) {
                            end++
                        }
                        val block = payload.copyOfRange(offset, end)
                        PtyNative.writeSession(handle, block)
                        offset = end
                        if (offset < total) {
                            delay(2L)
                        }
                    }
                }
            }
        }
    }

    fun scrollBy(deltaLines: Int) {
        val maxScroll = buffer.historySize
        val current = _scrollOffset.value
        val target = (current + deltaLines).coerceIn(0, maxScroll)
        if (target != current) {
            _scrollOffset.value = target
            _renderTick.value = System.nanoTime()
        }
    }

    fun scrollToBottom() {
        if (_scrollOffset.value != 0) {
            _scrollOffset.value = 0
            _renderTick.value = System.nanoTime()
        }
    }

    private var lastAppliedPtyRows: Int = -1
    private var lastAppliedPtyCols: Int = -1

    fun start() {
        if (nativeHandle != 0L) return
        nativeHandle = PtyNative.startSession(
            cmd = launchConfig.cmd,
            args = launchConfig.args,
            env = launchConfig.env,
            cwd = launchConfig.cwd,
            rows = buffer.rows,
            cols = buffer.cols,
            listener = this
        )
        if (nativeHandle != 0L) {
            lastAppliedPtyRows = buffer.rows
            lastAppliedPtyCols = buffer.cols
            PtyNative.resizeSession(nativeHandle, buffer.rows, buffer.cols)
        }
        val tid = PtyNative.readerTid(nativeHandle)
        if (tid > 0) {
            readerTid = tid
            performanceManager.attachReaderThread(tid)
        }
    }

    fun write(bytes: ByteArray) {
        if (nativeHandle == 0L || !_isAlive.value || bytes.isEmpty()) return
        scrollToBottom()
        writeChannel.trySend(bytes)
    }

    /**
     * Debounces TIOCSWINSZ resize events during keyboard animations (80ms settle window)
     * and deterministically dispatches SIGWINCH to both buffer and active PTY.
     */
    fun requestResizeDebounced(rows: Int, cols: Int) {
        val clampedRows = rows.coerceIn(6, 200)
        val clampedCols = cols.coerceIn(20, 300)
        val bufferMatches = (clampedRows == buffer.rows && clampedCols == buffer.cols)
        val ptyMatches = (clampedRows == lastAppliedPtyRows && clampedCols == lastAppliedPtyCols)
        if (bufferMatches && ptyMatches) return

        resizeDebounceJob?.cancel()
        resizeDebounceJob = scope.launch {
            delay(80L)
            buffer.resize(clampedRows, clampedCols)
            if (nativeHandle != 0L && _isAlive.value) {
                PtyNative.resizeSession(nativeHandle, clampedRows, clampedCols)
                lastAppliedPtyRows = clampedRows
                lastAppliedPtyCols = clampedCols
            }
            _renderTick.value = System.nanoTime()
        }
    }

    private var pendingFrameJob: Job? = null

    fun close() {
        resizeDebounceJob?.cancel()
        pendingFrameJob?.cancel()
        writeChannel.close()
        if (readerTid > 0) {
            performanceManager.detachReaderThread(readerTid)
            readerTid = 0
        }
        if (nativeHandle != 0L) {
            PtyNative.killSession(nativeHandle, 15) // SIGTERM
            nativeHandle = 0L
        }
        _isAlive.value = false
    }

    override fun onOutput(data: ByteArray) {
        val startNs = System.nanoTime()
        buffer.appendBytes(data, paletteProvider())
        val durationNs = System.nanoTime() - startNs
        performanceManager.reportWorkDuration(durationNs)

        // Coalesce frame ticks so burst output (e.g. 800-line paste echo) never floods recompositions
        val nowMs = System.currentTimeMillis()
        val minIntervalMs = performanceManager.frameIntervalMs.value.coerceAtLeast(16L)
        if (nowMs - lastFrameEmitMs >= minIntervalMs) {
            lastFrameEmitMs = nowMs
            pendingFrameJob?.cancel()
            pendingFrameJob = null
            _renderTick.value = System.nanoTime()
        } else if (pendingFrameJob?.isActive != true) {
            val waitMs = (minIntervalMs - (nowMs - lastFrameEmitMs)).coerceAtLeast(8L)
            pendingFrameJob = scope.launch {
                delay(waitMs)
                lastFrameEmitMs = System.currentTimeMillis()
                _renderTick.value = System.nanoTime()
            }
        }
    }

    override fun onExit(code: Int) {
        if (readerTid > 0) {
            performanceManager.detachReaderThread(readerTid)
            readerTid = 0
        }
        _isAlive.value = false
        _exitCode.value = code
        val exitBanner = "\r\n\u001B[38;2;140;136;129m[Processo encerrado (código $code)]\u001B[0m\r\n"
        buffer.appendBytes(exitBanner.toByteArray(Charsets.UTF_8), paletteProvider())
        _renderTick.value = System.nanoTime()
    }

    override fun onReaderStarted(tid: Int) {
        readerTid = tid
        performanceManager.attachReaderThread(tid)
    }
}
