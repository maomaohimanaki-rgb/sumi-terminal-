package dev.sumi.terminal.service

import dev.sumi.terminal.data.repository.WorkspaceRepository
import dev.sumi.terminal.perf.PerformanceManager
import dev.sumi.terminal.ui.theme.SumiPalettes
import dev.sumi.terminal.ui.theme.WabiSabiPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionManager(
    private val workspaceRepository: WorkspaceRepository,
    private val performanceManager: PerformanceManager
) {
    private val tabCounter = AtomicInteger(0)

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    @Volatile
    var currentPalette: WabiSabiPalette = SumiPalettes.Carvao

    fun ensureDefaultSession(): TerminalSession {
        val current = _sessions.value
        if (current.isNotEmpty()) {
            val active = current.firstOrNull { it.id == _activeSessionId.value } ?: current.first()
            _activeSessionId.value = active.id
            return active
        }
        return createNewSession()
    }

    fun createNewSession(customTitle: String? = null): TerminalSession {
        val num = tabCounter.incrementAndGet()
        val id = UUID.randomUUID().toString()
        val title = customTitle ?: "zsh:$num"
        val session = TerminalSession(
            id = id,
            index = num,
            initialTitle = title,
            workingDir = workspaceRepository.homeDir,
            performanceManager = performanceManager,
            paletteProvider = { currentPalette }
        )
        session.start()
        _sessions.value = _sessions.value + session
        _activeSessionId.value = session.id
        return session
    }

    fun selectSession(id: String) {
        if (_sessions.value.any { it.id == id }) {
            _activeSessionId.value = id
        }
    }

    fun closeSession(id: String) {
        val list = _sessions.value
        val target = list.firstOrNull { it.id == id } ?: return
        target.close()
        val remaining = list.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            _sessions.value = emptyList()
            createNewSession()
        } else {
            _sessions.value = remaining
            if (_activeSessionId.value == id) {
                _activeSessionId.value = remaining.last().id
            }
        }
    }

    fun getActiveSession(): TerminalSession? {
        val id = _activeSessionId.value
        return _sessions.value.firstOrNull { it.id == id } ?: _sessions.value.firstOrNull()
    }
}
