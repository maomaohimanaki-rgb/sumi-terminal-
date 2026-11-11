package dev.sumi.terminal.install

data class InstallState(
    val phase: InstallPhase = InstallPhase.CHECKING,
    val progress: Float = 0f,
    val logLines: List<String> = emptyList(),
    val isComplete: Boolean = false,
    val error: String? = null
)
