package dev.sumi.terminal.ui.files

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sumi.terminal.files.ClipboardMode
import dev.sumi.terminal.files.FileItem
import dev.sumi.terminal.files.MimeTypeUtils
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi
import java.io.File

@Composable
fun FileManagerScreen(
    viewModel: FileManagerViewModel,
    onOpenFileInZenEditor: (File) -> Unit,
    onOpenDirInTerminal: (File) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()
    val palette = LocalWabiSabiPalette.current

    var showCreateDialog by remember { mutableStateOf<Boolean?>(null) } // true = file, false = folder
    var newItemName by remember { mutableStateOf("") }

    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var renameErrorText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importExternalUrisToCurrentDir(context, uris)
        }
    }

    BackHandler {
        if (state.selectedPaths.isNotEmpty()) {
            viewModel.clearSelection()
        } else if (!viewModel.navigateUp()) {
            onBack()
        }
    }

    fun openRenameDialog(file: File) {
        renameTargetFile = file
        renameInputText = file.name
        renameErrorText = null
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }.onFailure {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
    }

    val isInsideExternalStorage = state.currentDir.absolutePath.startsWith("/storage") ||
            state.currentDir.absolutePath.startsWith("/sdcard")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("file_manager_screen")
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(palette.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (state.selectedPaths.isNotEmpty()) {
                        viewModel.clearSelection()
                    } else {
                        onBack()
                    }
                },
                modifier = Modifier.testTag("files_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = Sumi.Paper
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.selectedPaths.isNotEmpty()) {
                        "${state.selectedPaths.size} selecionado(s)"
                    } else {
                        "Gerenciador Cross-FS"
                    },
                    color = Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                val formattedPath = state.currentDir.absolutePath
                    .replace(state.rootfsDir.absolutePath, "[Arch] /")
                    .replace("//", "/")
                Text(
                    text = formattedPath,
                    color = palette.directoryHighlight,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            IconButton(
                onClick = { importDocumentLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.testTag("import_external_files_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = "Importar arquivo externo para pasta atual",
                    tint = palette.primaryAccent
                )
            }

            IconButton(
                onClick = { onOpenDirInTerminal(state.currentDir) },
                modifier = Modifier.testTag("open_cwd_in_terminal_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = "Abrir pasta no terminal",
                    tint = Sumi.Shironeri
                )
            }

            IconButton(
                onClick = {
                    newItemName = ""
                    showCreateDialog = false
                },
                modifier = Modifier.testTag("new_folder_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CreateNewFolder,
                    contentDescription = "Nova pasta",
                    tint = Sumi.Shironeri
                )
            }

            IconButton(
                onClick = {
                    newItemName = ""
                    showCreateDialog = true
                },
                modifier = Modifier.testTag("new_file_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Novo arquivo no Zen Editor",
                    tint = palette.primaryAccent
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )

        // Storage Bookmarks Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface.copy(alpha = 0.7f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StorageBookmark.entries.forEach { bm ->
                val isCurrent = when (bm) {
                    StorageBookmark.ARCH_ROOT -> state.currentDir.absolutePath == state.rootfsDir.absolutePath
                    StorageBookmark.ARCH_HOME -> state.currentDir.absolutePath == state.archHomeDir.absolutePath
                    StorageBookmark.DOWNLOADS -> state.currentDir.absolutePath == state.downloadsDir.absolutePath
                    StorageBookmark.SDCARD -> state.currentDir.absolutePath == state.sdcardDir.absolutePath
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isCurrent) palette.primaryAccent.copy(alpha = 0.2f) else palette.background)
                        .border(
                            Sumi.HairlineWidth,
                            if (isCurrent) palette.primaryAccent else Sumi.Hairline,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { viewModel.jumpToBookmark(bm) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("bookmark_${bm.name}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = bm.label,
                        color = if (isCurrent) palette.primaryAccent else Sumi.Shironeri,
                        fontFamily = Sumi.Mono,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                }
            }

            if (state.selectedPaths.isNotEmpty()) {
                Text(
                    text = if (state.selectedPaths.size == state.items.size) "Limpar seleção" else "Selecionar tudo",
                    color = palette.primaryAccent,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable {
                            if (state.selectedPaths.size == state.items.size) {
                                viewModel.clearSelection()
                            } else {
                                viewModel.selectAllInCurrentDir()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )

        // MANAGE_EXTERNAL_STORAGE Persistent Banner when accessing /storage without permission
        if (!state.hasAllFilesAccess && isInsideExternalStorage) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Sumi.Kurenai.copy(alpha = 0.16f))
                    .border(Sumi.HairlineWidth, Sumi.Kurenai.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("manage_storage_permission_banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Permissão de Acesso Total Necessária",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Autorize MANAGE_EXTERNAL_STORAGE para copiar/mover livremente de /sdcard e Download para o Arch.",
                        color = Sumi.Shironeri,
                        fontFamily = Sumi.Sans,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Sumi.Kurenai)
                        .clickable { requestAllFilesAccess() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("grant_all_files_button")
                ) {
                    Text(
                        text = "Autorizar",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Long-Press Selection Action Bar (Copiar / Mover / Renomear / Excluir)
        if (state.selectedPaths.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Sumi.Sumi2)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("file_selection_action_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionButtonChip(
                    icon = Icons.Outlined.ContentCopy,
                    label = "Copiar (${state.selectedPaths.size})",
                    accentColor = palette.primaryAccent,
                    onClick = { viewModel.stageClipboard(ClipboardMode.COPY) }
                )
                ActionButtonChip(
                    icon = Icons.Outlined.DriveFileMove,
                    label = "Mover (${state.selectedPaths.size})",
                    accentColor = palette.directoryHighlight,
                    onClick = { viewModel.stageClipboard(ClipboardMode.MOVE) }
                )
                if (state.selectedPaths.size == 1) {
                    val singleFile = File(state.selectedPaths.first())
                    ActionButtonChip(
                        icon = Icons.Outlined.Edit,
                        label = "Renomear",
                        accentColor = Sumi.Shironeri,
                        onClick = { openRenameDialog(singleFile) }
                    )
                }
                ActionButtonChip(
                    icon = Icons.Outlined.DeleteOutline,
                    label = "Excluir",
                    accentColor = Sumi.Kurenai,
                    onClick = { viewModel.deleteSelectedOrSingle() }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sumi.HairlineWidth)
                    .background(Sumi.Hairline)
            )
        }

        // Global FileClipboard Bar ("Colar aqui")
        if (!state.clipboard.isEmpty) {
            val modeLabel = if (state.clipboard.mode == ClipboardMode.COPY) "Cópia" else "Movimentação"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.primaryAccent.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .testTag("file_clipboard_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$modeLabel pendente: ${state.clipboard.count} item(ns)",
                        color = Sumi.Paper,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Destino atual: ${state.currentDir.absolutePath}",
                        color = Sumi.Muted,
                        fontFamily = Sumi.Mono,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.primaryAccent)
                            .clickable { viewModel.pasteClipboardHere() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("paste_files_here_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                tint = Sumi.Sumi1,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Colar aqui",
                                color = Sumi.Sumi1,
                                fontFamily = Sumi.Mono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.clearClipboard() }) {
                        Text("Cancelar", color = Sumi.Muted, fontFamily = Sumi.Mono, fontSize = 11.sp)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sumi.HairlineWidth)
                    .background(Sumi.Hairline)
            )
        }

        // Status feedback banner
        state.statusBanner?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Sumi.Sumi2)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = msg,
                    color = palette.primaryAccent,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
            }
        }

        // Parent Directory (..) Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface.copy(alpha = 0.5f))
                .clickable { viewModel.navigateUp() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ".. (Diretório pai)",
                color = palette.directoryHighlight,
                fontFamily = Sumi.Mono,
                fontSize = 12.sp
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sumi.HairlineWidth)
                .background(Sumi.Hairline)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = state.items, key = { it.absolutePath }) { item ->
                val isSelected = state.selectedPaths.contains(item.absolutePath)
                FileRowCard(
                    item = item,
                    isSelected = isSelected,
                    onClick = {
                        if (state.selectedPaths.isNotEmpty()) {
                            viewModel.toggleSelection(item)
                        } else if (item.isDirectory) {
                            viewModel.navigateTo(item.file)
                        } else if (MimeTypeUtils.isEditableText(item.file)) {
                            onOpenFileInZenEditor(item.file)
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleSelection(item)
                    },
                    onRename = { openRenameDialog(item.file) },
                    onDelete = { viewModel.deleteSelectedOrSingle(item) }
                )
            }
        }
    }

    // Create File / Folder Dialog
    showCreateDialog?.let { isFile ->
        AlertDialog(
            onDismissRequest = { showCreateDialog = null },
            containerColor = Sumi.Sumi2,
            titleContentColor = Sumi.Paper,
            title = {
                Text(
                    text = if (isFile) "Novo Arquivo (Zen Editor)" else "Novo Diretório",
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            },
            text = {
                OutlinedTextField(
                    value = newItemName,
                    onValueChange = { newItemName = it },
                    placeholder = {
                        Text(
                            if (isFile) "ex: script.sh ou main.rs" else "ex: src",
                            color = Sumi.Muted,
                            fontFamily = Sumi.Mono,
                            fontSize = 12.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newItemName.trim()
                        showCreateDialog = null
                        if (name.isNotEmpty()) {
                            if (isFile) {
                                viewModel.createNewFile(name) { created ->
                                    onOpenFileInZenEditor(created)
                                }
                            } else {
                                viewModel.createNewFolder(name)
                            }
                        }
                    }
                ) {
                    Text("Criar", color = palette.primaryAccent, fontFamily = Sumi.Mono, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = null }) {
                    Text("Cancelar", color = Sumi.Muted, fontFamily = Sumi.Mono)
                }
            }
        )
    }

    // Rename File / Folder Dialog with Validation
    renameTargetFile?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTargetFile = null },
            containerColor = Sumi.Sumi2,
            titleContentColor = Sumi.Paper,
            title = {
                Text(
                    text = "Renomear '${target.name}'",
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = {
                            renameInputText = it
                            renameErrorText = null
                        },
                        placeholder = {
                            Text(
                                "Novo nome",
                                color = Sumi.Muted,
                                fontFamily = Sumi.Mono,
                                fontSize = 12.sp
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_input_field")
                    )
                    renameErrorText?.let { err ->
                        Text(
                            text = err,
                            color = Sumi.Kurenai,
                            fontFamily = Sumi.Mono,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val candidate = renameInputText.trim()
                        when {
                            candidate.isEmpty() -> {
                                renameErrorText = "O nome não pode ficar vazio."
                            }
                            candidate.contains('/') || candidate == "." || candidate == ".." -> {
                                renameErrorText = "Nome inválido (não use '/')."
                            }
                            else -> {
                                val sibling = File(target.parentFile, candidate)
                                if (sibling.exists() && sibling.absolutePath != target.absolutePath) {
                                    renameErrorText = "Já existe um item com esse nome."
                                } else {
                                    viewModel.renameItem(target, candidate)
                                    renameTargetFile = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("rename_confirm_button")
                ) {
                    Text("Renomear", color = palette.primaryAccent, fontFamily = Sumi.Mono, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetFile = null }) {
                    Text("Cancelar", color = Sumi.Muted, fontFamily = Sumi.Mono)
                }
            }
        )
    }
}

@Composable
private fun ActionButtonChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(accentColor.copy(alpha = 0.16f))
            .border(Sumi.HairlineWidth, accentColor.copy(alpha = 0.6f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            color = accentColor,
            fontFamily = Sumi.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRowCard(
    item: FileItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalWabiSabiPalette.current
    val badge = if (item.isSymlink) "LNK" else MimeTypeUtils.badgeForFile(item.file)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) palette.selectionBackground.copy(alpha = 0.65f) else palette.surface)
            .border(
                width = if (isSelected) 1.5.dp else Sumi.HairlineWidth,
                color = if (isSelected) palette.primaryAccent else Sumi.Hairline,
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("file_item_${item.name}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (item.isDirectory) palette.directoryHighlight.copy(alpha = 0.16f)
                    else palette.primaryAccent.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (item.isDirectory) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = palette.directoryHighlight,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = badge,
                    color = palette.primaryAccent,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (item.isDirectory) "${item.name}/" else item.name,
                color = if (item.isDirectory) palette.directoryHighlight else Sumi.Paper,
                fontFamily = Sumi.Mono,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${item.permissionsString()}  •  ${item.formattedSize()}  •  ${item.formattedDate()}",
                color = Sumi.Muted,
                fontFamily = Sumi.Mono,
                fontSize = 10.sp
            )
        }

        IconButton(
            onClick = onRename,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Renomear ${item.name}",
                tint = Sumi.Muted,
                modifier = Modifier.size(17.dp)
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "Remover ${item.name}",
                tint = Sumi.Muted,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
