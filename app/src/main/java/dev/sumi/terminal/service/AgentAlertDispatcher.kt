package dev.sumi.terminal.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TerminalAlert(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val message: String,
    val isError: Boolean = false
)

class AgentAlertDispatcher {
    private val _currentAlert = MutableStateFlow<TerminalAlert?>(null)
    val currentAlert: StateFlow<TerminalAlert?> = _currentAlert.asStateFlow()

    fun dispatch(title: String, message: String, isError: Boolean = false) {
        _currentAlert.value = TerminalAlert(
            title = title,
            message = message,
            isError = isError
        )
    }

    fun dismiss() {
        _currentAlert.value = null
    }
}
