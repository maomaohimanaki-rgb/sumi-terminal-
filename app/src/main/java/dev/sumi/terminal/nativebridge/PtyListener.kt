package dev.sumi.terminal.nativebridge

interface PtyListener {
    fun onOutput(data: ByteArray)
    fun onExit(code: Int)
    fun onReaderStarted(tid: Int)
}
