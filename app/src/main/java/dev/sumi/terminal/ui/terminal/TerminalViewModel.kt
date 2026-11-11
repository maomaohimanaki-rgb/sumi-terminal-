package dev.sumi.terminal.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sumi.terminal.data.preferences.CursorStyle
import dev.sumi.terminal.data.preferences.TerminalPreferences
import dev.sumi.terminal.data.preferences.TerminalSettings
import dev.sumi.terminal.perf.PerformanceManager
import dev.sumi.terminal.perf.ThermalState
import dev.sumi.terminal.service.TerminalSession
import dev.sumi.terminal.service.TerminalSessionManager
import dev.sumi.terminal.terminal.KeyEncoder
import dev.sumi.terminal.terminal.Mods
import dev.sumi.terminal.terminal.TermKey
import dev.sumi.terminal.terminal.TerminalSelection
import dev.sumi.terminal.ui.theme.SumiPalettes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class TerminalViewModel(
    private val sessionManager: TerminalSessionManager,
    private val preferences: TerminalPreferences,
    performanceManager: PerformanceManager
) : ViewModel() {

    val sessions: StateFlow<List<TerminalSession>> = sessionManager.sessions
    val activeSessionId: StateFlow<String?> = sessionManager.activeSessionId
    val thermalState: StateFlow<ThermalState> = performanceManager.thermalState

    val settings: StateFlow<TerminalSettings> = preferences.settings
        .onEach { s ->
            sessionManager.currentPalette = SumiPalettes.byId(s.paletteName)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TerminalSettings()
        )

    private val _mods = MutableStateFlow(Mods())
    val mods: StateFlow<Mods> = _mods.asStateFlow()

    private val _selection = MutableStateFlow<TerminalSelection?>(null)
    val selection: StateFlow<TerminalSelection?> = _selection.asStateFlow()

    private val _pendingSafePaste = MutableStateFlow<String?>(null)
    val pendingSafePaste: StateFlow<String?> = _pendingSafePaste.asStateFlow()

    private val _showAppearance = MutableStateFlow(false)
    val showAppearance: StateFlow<Boolean> = _showAppearance.asStateFlow()

    init {
        sessionManager.ensureDefaultSession()
    }

    fun selectTab(id: String) {
        _selection.value = null
        sessionManager.selectSession(id)
    }

    fun createNewTab() {
        _selection.value = null
        sessionManager.createNewSession()
    }

    fun closeTab(id: String) {
        _selection.value = null
        sessionManager.closeSession(id)
    }

    fun toggleCtrl() {
        _mods.value = _mods.value.copy(ctrl = !_mods.value.ctrl)
    }

    fun toggleAlt() {
        _mods.value = _mods.value.copy(alt = !_mods.value.alt)
    }

    private fun consumeMods(): Mods {
        val current = _mods.value
        if (current.isAnyActive) {
            _mods.value = Mods()
        }
        return current
    }

    fun sendKey(key: TermKey) {
        val active = sessionManager.getActiveSession() ?: return
        val bytes = KeyEncoder.encode(key, consumeMods())
        active.write(bytes)
    }

    fun sendRawText(text: String) {
        if (text.isEmpty()) return
        val active = sessionManager.getActiveSession() ?: return
        val bytes = KeyEncoder.encodeText(text, consumeMods())
        active.write(bytes)
    }

    fun sendBytes(bytes: ByteArray) {
        val active = sessionManager.getActiveSession() ?: return
        active.write(bytes)
    }

    fun runQuickCommand(cmd: String) {
        val active = sessionManager.getActiveSession() ?: return
        active.write((cmd + "\r").toByteArray(Charsets.UTF_8))
    }

    fun cdToDirectory(dir: File) {
        val active = sessionManager.getActiveSession() ?: return
        active.write(("cd ${dir.absolutePath} && ls -la\r").toByteArray(Charsets.UTF_8))
    }

    fun runScriptFile(file: File) {
        val active = sessionManager.getActiveSession() ?: return
        val command = when (file.extension.lowercase()) {
            "rs" -> "cargo run"
            "sh", "bash" -> "sh ${file.absolutePath}"
            else -> "cat ${file.absolutePath}"
        }
        active.write(("$command\r").toByteArray(Charsets.UTF_8))
    }

    /**
     * Specification A (Safe Paste Mode):
     * Any pasted text > 80 characters or containing a newline ('\n' or '\r')
     * opens the PasteConfirmDialog before sending to the PTY.
     */
    fun onPasteRequested(text: String) {
        if (text.isEmpty()) return
        if (text.length > 80 || text.contains('\n') || text.contains('\r')) {
            _pendingSafePaste.value = text
        } else {
            sendRawText(text)
        }
    }

    fun confirmSafePaste(bracketed: Boolean) {
        val text = _pendingSafePaste.value ?: return
        _pendingSafePaste.value = null
        val active = sessionManager.getActiveSession() ?: return
        val payload = KeyEncoder.wrapBracketedPaste(text, bracketed)
        active.write(payload)
    }

    fun dismissSafePaste() {
        _pendingSafePaste.value = null
    }

    fun updateSelection(newSelection: TerminalSelection?) {
        _selection.value = newSelection
    }

    fun setAppearanceVisible(visible: Boolean) {
        _showAppearance.value = visible
    }

    fun setPalette(name: String) = viewModelScope.launch { preferences.setPalette(name) }
    fun setBackgroundPath(path: String?) = viewModelScope.launch { preferences.setCustomBackground(path) }
    fun setBrightness(value: Float) = viewModelScope.launch { preferences.setBrightness(value) }
    fun setFontSize(sizeSp: Float) = viewModelScope.launch { preferences.setFontSize(sizeSp) }
    fun setCursorStyle(style: CursorStyle) = viewModelScope.launch { preferences.setCursorStyle(style) }
    fun setAutoColors(enabled: Boolean) = viewModelScope.launch { preferences.setAutoColors(enabled) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { preferences.setKeepScreenOn(enabled) }
}
