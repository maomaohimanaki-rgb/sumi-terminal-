package dev.sumi.terminal.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EditorUiState(
    val file: File? = null,
    val content: TextFieldValue = TextFieldValue(""),
    val isLoaded: Boolean = false,
    val isDirty: Boolean = false,
    val statusMessage: String = "Pronto"
)

class EditorViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun openFile(target: File) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { target.readText(Charsets.UTF_8) }.getOrDefault("")
            }
            _uiState.value = EditorUiState(
                file = target,
                content = TextFieldValue(text),
                isLoaded = true,
                isDirty = false,
                statusMessage = "Pronto"
            )
        }
    }

    fun updateContent(newValue: TextFieldValue) {
        val current = _uiState.value
        val textChanged = newValue.text != current.content.text
        _uiState.value = current.copy(
            content = newValue,
            isDirty = current.isDirty || textChanged,
            statusMessage = if (current.isDirty || textChanged) "Editando" else current.statusMessage
        )
    }

    fun insertSymbol(symbol: String) {
        val current = _uiState.value
        val ins = if (symbol == "Tab") "    " else symbol
        val sel = current.content.selection
        val start = sel.min.coerceIn(0, current.content.text.length)
        val end = sel.max.coerceIn(0, current.content.text.length)
        val updatedText = current.content.text.replaceRange(start, end, ins)
        val updatedValue = current.content.copy(
            text = updatedText,
            selection = TextRange(start + ins.length)
        )
        updateContent(updatedValue)
    }

    fun saveAtomically() {
        val target = _uiState.value.file ?: return
        val textToSave = _uiState.value.content.text
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tmpFile = File(target.parentFile, ".${target.name}.sumi.tmp")
                tmpFile.writeText(textToSave, Charsets.UTF_8)
                if (!tmpFile.renameTo(target)) {
                    target.writeText(textToSave, Charsets.UTF_8)
                    tmpFile.delete()
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isDirty = false,
                        statusMessage = "Salvo"
                    )
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Erro ao salvar!"
                    )
                }
            }
        }
    }
}
