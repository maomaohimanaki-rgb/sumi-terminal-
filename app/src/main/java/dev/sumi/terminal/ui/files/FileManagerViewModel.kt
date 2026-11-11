package dev.sumi.terminal.ui.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sumi.terminal.data.repository.WorkspaceRepository
import dev.sumi.terminal.files.ClipboardMode
import dev.sumi.terminal.files.FileClipboard
import dev.sumi.terminal.files.FileItem
import dev.sumi.terminal.files.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class StorageBookmark(val label: String) {
    ARCH_ROOT("Arch /"),
    ARCH_HOME("/root"),
    DOWNLOADS("Download"),
    SDCARD("/sdcard")
}

data class FileManagerUiState(
    val currentDir: File,
    val rootfsDir: File,
    val archHomeDir: File,
    val sdcardDir: File,
    val downloadsDir: File,
    val items: List<FileItem> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val clipboard: FileClipboard = FileClipboard(),
    val showHidden: Boolean = true,
    val hasAllFilesAccess: Boolean = true,
    val isLoading: Boolean = false,
    val statusBanner: String? = null
)

class FileManagerViewModel(
    workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val rootfs = workspaceRepository.rootfsDir
    private val archHome = workspaceRepository.rootHomeDir
    private val sdcard = workspaceRepository.sdcardDir
    private val downloads = workspaceRepository.downloadsDir

    private val _uiState = MutableStateFlow(
        FileManagerUiState(
            currentDir = archHome,
            rootfsDir = rootfs,
            archHomeDir = archHome,
            sdcardDir = sdcard,
            downloadsDir = downloads,
            hasAllFilesAccess = checkAllFilesPermission()
        )
    )
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun checkAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun refresh() {
        val dir = _uiState.value.currentDir
        val showHidden = _uiState.value.showHidden
        val hasPerm = checkAllFilesPermission()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                hasAllFilesAccess = hasPerm
            )
            val list = FileOps.listDirectory(dir, showHidden)
            _uiState.value = _uiState.value.copy(
                items = list,
                isLoading = false
            )
        }
    }

    fun jumpToBookmark(bookmark: StorageBookmark) {
        val target = when (bookmark) {
            StorageBookmark.ARCH_ROOT -> rootfs
            StorageBookmark.ARCH_HOME -> archHome
            StorageBookmark.DOWNLOADS -> downloads
            StorageBookmark.SDCARD -> sdcard
        }
        navigateTo(target)
    }

    fun navigateTo(dir: File) {
        _uiState.value = _uiState.value.copy(
            currentDir = dir,
            selectedPaths = emptySet(),
            statusBanner = null,
            hasAllFilesAccess = checkAllFilesPermission()
        )
        refresh()
    }

    fun navigateUp(): Boolean {
        val cur = _uiState.value.currentDir
        val parent = cur.parentFile
        val stopRoots = setOf(
            rootfs.absolutePath,
            sdcard.absolutePath,
            "/"
        )
        return if (parent != null && cur.absolutePath !in stopRoots) {
            navigateTo(parent)
            true
        } else {
            false
        }
    }

    fun toggleSelection(item: FileItem) {
        val current = _uiState.value.selectedPaths.toMutableSet()
        val path = item.absolutePath
        if (current.contains(path)) {
            current.remove(path)
        } else {
            current.add(path)
        }
        _uiState.value = _uiState.value.copy(selectedPaths = current)
    }

    fun selectAllInCurrentDir() {
        val all = _uiState.value.items.map { it.absolutePath }.toSet()
        _uiState.value = _uiState.value.copy(selectedPaths = all)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPaths = emptySet())
    }

    fun renameItem(target: File, newNameRaw: String) {
        val trimmed = newNameRaw.trim()
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed == "." || trimmed == "..") {
            _uiState.value = _uiState.value.copy(
                statusBanner = "Erro: Nome inválido ('$trimmed')."
            )
            return
        }
        val parent = target.parentFile ?: _uiState.value.currentDir
        val dest = File(parent, trimmed)
        if (dest.exists() && dest.absolutePath != target.absolutePath) {
            _uiState.value = _uiState.value.copy(
                statusBanner = "Erro: Já existe um item chamado '$trimmed'."
            )
            return
        }

        viewModelScope.launch {
            FileOps.rename(target, trimmed).onSuccess { renamed ->
                _uiState.value = _uiState.value.copy(
                    selectedPaths = emptySet(),
                    statusBanner = "✓ Renomeado para '${renamed.name}'."
                )
                refresh()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    statusBanner = "Erro ao renomear: ${err.message}"
                )
            }
        }
    }

    fun stageClipboard(mode: ClipboardMode) {
        val selectedFiles = _uiState.value.selectedPaths.map { File(it) }.filter { it.exists() || FileOps.isSymlink(it) }
        if (selectedFiles.isEmpty()) return
        val actionLabel = if (mode == ClipboardMode.COPY) "copiar" else "mover"
        _uiState.value = _uiState.value.copy(
            clipboard = FileClipboard(sources = selectedFiles, mode = mode),
            selectedPaths = emptySet(),
            statusBanner = "${selectedFiles.size} item(ns) prontos para $actionLabel. Navegue até o destino e toque em 'Colar aqui'."
        )
    }

    fun clearClipboard() {
        _uiState.value = _uiState.value.copy(
            clipboard = FileClipboard(),
            statusBanner = null
        )
    }

    fun pasteClipboardHere() {
        val clip = _uiState.value.clipboard
        if (clip.isEmpty) return
        val destDir = _uiState.value.currentDir

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = if (clip.mode == ClipboardMode.COPY) {
                FileOps.copyBatchCrossFilesystem(clip.sources, destDir)
            } else {
                FileOps.moveBatchCrossFilesystem(clip.sources, destDir)
            }
            result.onSuccess { count ->
                val verb = if (clip.mode == ClipboardMode.COPY) "copiado(s)" else "movido(s)"
                _uiState.value = _uiState.value.copy(
                    clipboard = FileClipboard(),
                    isLoading = false,
                    statusBanner = "✓ $count item(ns) $verb para ${destDir.name.ifEmpty { "/" }}."
                )
                refresh()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusBanner = "Erro ao colar: ${err.message}"
                )
            }
        }
    }

    fun deleteSelectedOrSingle(singleItem: FileItem? = null) {
        val targets = if (singleItem != null) {
            listOf(singleItem.file)
        } else {
            _uiState.value.selectedPaths.map { File(it) }
        }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            FileOps.deleteBatch(targets).onSuccess { count ->
                _uiState.value = _uiState.value.copy(
                    selectedPaths = emptySet(),
                    statusBanner = "✓ $count item(ns) removido(s)."
                )
                refresh()
            }
        }
    }

    fun importExternalUrisToCurrentDir(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val destDir = _uiState.value.currentDir
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val importedCount = withContext(Dispatchers.IO) {
                var count = 0
                for (uri in uris) {
                    runCatching {
                        val fileName = queryDisplayName(context, uri) ?: "import_${System.currentTimeMillis()}"
                        val target = File(destDir, fileName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output, 64 * 1024)
                            }
                        }
                        count++
                    }
                }
                count
            }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusBanner = "✓ $importedCount arquivo(s) importado(s) para ${destDir.name}."
            )
            refresh()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
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
}
