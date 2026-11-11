package dev.sumi.terminal.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale

@Composable
fun ReleaseCenterDialog(
    workspaceHomeDir: File,
    onDismiss: () -> Unit,
    onRunReleaseCheckInTerminal: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalWabiSabiPalette.current
    val scope = rememberCoroutineScope()

    val installedApkFile = remember(context) {
        runCatching { File(context.applicationInfo.sourceDir) }.getOrNull()
    }
    val apkSizeMb = remember(installedApkFile) {
        val bytes = installedApkFile?.length() ?: 0L
        String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
    }

    var exportStatus by remember { mutableStateOf<String?>(null) }

    val saveApkDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        if (uri != null && installedApkFile != null && installedApkFile.exists()) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(installedApkFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        exportStatus = "✓ APK salvo com sucesso no armazenamento!"
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        exportStatus = "Erro ao exportar APK: ${err.message}"
                    }
                }
            }
        }
    }

    fun copyApkToWorkspace() {
        val src = installedApkFile ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val releaseDir = File(workspaceHomeDir, "release").apply { mkdirs() }
                val target = File(releaseDir, "sumi-terminal-v2.0-zen-release.apk")
                src.copyTo(target, overwrite = true)
                target
            }.onSuccess { dest ->
                withContext(Dispatchers.Main) {
                    exportStatus = "✓ Copiado para ~/release/${dest.name} (${apkSizeMb})"
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    exportStatus = "Erro: ${err.message}"
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sumi.Sumi2)
                .border(Sumi.HairlineWidth, palette.primaryAccent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(20.dp)
                .testTag("release_center_dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.primaryAccent.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "APK & KEYSTORE",
                        color = palette.primaryAccent,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "Download Fácil • v2.0-zen",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            // Metadados do APK e KeyStore
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Sumi.Sumi1)
                    .border(Sumi.HairlineWidth, Sumi.Hairline, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "Pacote: sumi-terminal-v2.0-zen-release.apk ($apkSizeMb)",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
                Text(
                    text = "Pasta Raiz no Editor: /DOWNLOAD_AQUI/",
                    color = palette.primaryAccent,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
                Text(
                    text = "KeyStore: /DOWNLOAD_AQUI/sumi-release.jks (RSA 4096-bit)",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
                Text(
                    text = "Alias: sumi-key  |  Senha: sumiTerminal2026SecureKey",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 10.sp
                )
            }

            // Opção 1: Salvar APK diretamente pelo seletor de arquivos
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.primaryAccent)
                    .clickable {
                        saveApkDocumentLauncher.launch("sumi-terminal-v2.0-zen-release.apk")
                    }
                    .padding(vertical = 12.dp, horizontal = 14.dp)
                    .testTag("download_apk_direct_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⬇ Baixar / Salvar APK Agora ($apkSizeMb)",
                    color = Sumi.Sumi1,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            // Opção 2: Exportar para o Workspace Linux (~/release/)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Sumi.Sumi1)
                    .border(Sumi.HairlineWidth, palette.primaryAccent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable { copyApkToWorkspace() }
                    .padding(vertical = 10.dp, horizontal = 14.dp)
                    .testTag("copy_apk_workspace_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Copiar APK para ~/release/ no Terminal",
                    color = palette.primaryAccent,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
            }

            if (exportStatus != null) {
                Text(
                    text = exportStatus!!,
                    color = Sumi.Matcha,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
            }

            Text(
                text = "Dica para PC/Navegador: Abra a aba 'Code' (Explorador de Arquivos à esquerda) → pasta '/DOWNLOAD_AQUI/' e clique com botão direito em 'sumi-terminal-v2.0-zen-release.apk' → Download.",
                color = Sumi.Muted,
                fontFamily = Sumi.Sans,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        onRunReleaseCheckInTerminal()
                        onDismiss()
                    }
                ) {
                    Text("Inspecionar no Terminal", color = palette.directoryHighlight, fontFamily = Sumi.Mono, fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onDismiss) {
                    Text("Fechar", color = Sumi.Muted, fontFamily = Sumi.Mono, fontSize = 12.sp)
                }
            }
        }
    }
}
