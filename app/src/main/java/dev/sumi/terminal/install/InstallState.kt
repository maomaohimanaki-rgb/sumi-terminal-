package dev.sumi.terminal.install

data class InstallState(
    val phase: InstallPhase = InstallPhase.IDLE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val extractedEntries: Int = 0,
    val currentEntry: String = "",
    val logLines: List<String> = emptyList(),
    val isComplete: Boolean = false,
    val isInstalling: Boolean = false,
    val error: String? = null
)
