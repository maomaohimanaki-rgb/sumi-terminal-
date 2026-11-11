package dev.sumi.terminal.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val state by viewModel.uiState.collectAsState()
    val palette = LocalWabiSabiPalette.current

    var showCreateDialog by remember { mutableStateOf<Boolean?>(null) } // true = file, false = folder
    var newItemName by remember { mutableStateOf("") }

    BackHandler {
        if (!viewModel.navigateUp()) {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("file_manager_screen")
    ) {
        // Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(palette.surface)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("files_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar ao Terminal",
                    tint = Sumi.Paper
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Workspace Arch Linux",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                val relPath = state.currentDir.absolutePath
                    .replace(state.rootHomeDir.absolutePath, "~")
                Text(
                    text = relPath,
                    color = palette.directoryHighlight,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            IconButton(
                onClick = { onOpenDirInTerminal(state.currentDir) },
                modifier = Modifier.testTag("open_cwd_in_terminal_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = "Abrir pasta no terminal",
                    tint = palette.primaryAccent
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

        // Up directory row if inside subdirectory
        if (state.currentDir.absolutePath != state.rootHomeDir.absolutePath) {
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
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = state.items, key = { it.file.absolutePath }) { item ->
                FileRowCard(
                    item = item,
                    onClick = {
                        if (item.isDirectory) {
                            viewModel.navigateTo(item.file)
                        } else if (MimeTypeUtils.isEditableText(item.file)) {
                            onOpenFileInZenEditor(item.file)
                        }
                    },
                    onDelete = { viewModel.deleteItem(item) }
                )
            }
        }
    }

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
}

@Composable
private fun FileRowCard(
    item: FileItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalWabiSabiPalette.current
    val badge = MimeTypeUtils.badgeForFile(item.file)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surface)
            .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
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
                fontWeight = FontWeight.Medium,
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

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "Remover ${item.name}",
                tint = Sumi.Muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
