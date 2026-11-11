package dev.sumi.terminal.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sumi.terminal.data.repository.WorkspaceRepository
import dev.sumi.terminal.files.FileItem
import dev.sumi.terminal.files.FileOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class FileManagerUiState(
    val currentDir: File,
    val rootHomeDir: File,
    val items: List<FileItem> = emptyList(),
    val showHidden: Boolean = true,
    val isLoading: Boolean = false,
    val message: String? = null
)

class FileManagerViewModel(
    workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val home = workspaceRepository.homeDir
    private val _uiState = MutableStateFlow(
        FileManagerUiState(
            currentDir = home,
            rootHomeDir = home
        )
    )
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val dir = _uiState.value.currentDir
        val showHidden = _uiState.value.showHidden
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val list = FileOps.listDirectory(dir, showHidden)
            _uiState.value = _uiState.value.copy(
                items = list,
                isLoading = false
            )
        }
    }

    fun navigateTo(dir: File) {
        if (dir.exists() && dir.isDirectory) {
            _uiState.value = _uiState.value.copy(currentDir = dir, message = null)
            refresh()
        }
    }

    fun navigateUp(): Boolean {
        val cur = _uiState.value.currentDir
        val parent = cur.parentFile
        return if (parent != null && parent.exists() && cur.absolutePath != _uiState.value.rootHomeDir.parentFile?.absolutePath) {
            navigateTo(parent)
            true
        } else {
            false
        }
    }

    fun toggleHidden() {
        _uiState.value = _uiState.value.copy(showHidden = !_uiState.value.showHidden)
        refresh()
    }

    fun createNewFile(name: String, onCreated: (File) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            FileOps.createNewFile(_uiState.value.currentDir, name).onSuccess { file ->
                refresh()
                onCreated(file)
            }
        }
    }

    fun createNewFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            FileOps.createNewDirectory(_uiState.value.currentDir, name).onSuccess {
                refresh()
            }
        }
    }

    fun deleteItem(item: FileItem) {
        viewModelScope.launch {
            FileOps.deleteRecursively(item.file).onSuccess {
                refresh()
            }
        }
    }
}
