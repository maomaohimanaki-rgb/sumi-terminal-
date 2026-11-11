package dev.sumi.terminal

import android.content.Intent
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sumi.terminal.service.TerminalDaemonService
import dev.sumi.terminal.ui.editor.ZenEditorScreen
import dev.sumi.terminal.ui.files.FileManagerScreen
import dev.sumi.terminal.ui.files.FileManagerViewModel
import dev.sumi.terminal.ui.setup.SetupScreen
import dev.sumi.terminal.ui.terminal.TerminalScreen
import dev.sumi.terminal.ui.terminal.TerminalViewModel
import dev.sumi.terminal.ui.theme.Sumi
import dev.sumi.terminal.ui.theme.SumiPalettes
import dev.sumi.terminal.ui.theme.SumiTheme
import kotlinx.coroutines.launch
import java.io.File

sealed interface SumiDestination {
    data object Terminal : SumiDestination
    data object Files : SumiDestination
    data class ZenEditor(val file: File) : SumiDestination
}

@Composable
fun SumiRootApp(app: SumiApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installState by app.rootfsInstaller.state.collectAsState()
    val currentAlert by app.alertDispatcher.currentAlert.collectAsState()

    val terminalViewModel = remember(app) {
        TerminalViewModel(
            sessionManager = app.sessionManager,
            preferences = app.preferences,
            performanceManager = app.performanceManager
        )
    }
    val fileManagerViewModel = remember(app) {
        FileManagerViewModel(workspaceRepository = app.workspaceRepository)
    }

    val settings by terminalViewModel.settings.collectAsState()
    val activePalette = remember(settings.paletteName) {
        SumiPalettes.byId(settings.paletteName)
    }

    var destination by remember { mutableStateOf<SumiDestination>(SumiDestination.Terminal) }
    var wasComplete by remember { mutableStateOf(app.rootfsInstaller.isInstalled()) }

    LaunchedEffect(Unit) {
        // Start hardened anti-sleep foreground daemon
        runCatching {
            val svcIntent = Intent(context, TerminalDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        }
        // Automatically start/verify Arch Linux ARM64 installation
        app.rootfsInstaller.startInstall()
    }

    // When installation transitions from incomplete to complete, reboot sessions into PRoot Arch Linux
    LaunchedEffect(installState.isComplete) {
        if (installState.isComplete && !wasComplete) {
            wasComplete = true
            app.sessionManager.restartAllSessionsWithProot()
        }
    }

    SumiTheme(palette = activePalette) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!installState.isComplete) {
                SetupScreen(
                    state = installState,
                    onStartOrRetryInstall = {
                        scope.launch { app.rootfsInstaller.startInstall() }
                    }
                )
            } else {
                Crossfade(targetState = destination, label = "sumiNav") { dest ->
                    when (dest) {
                        is SumiDestination.Terminal -> {
                            TerminalScreen(
                                viewModel = terminalViewModel,
                                onOpenFiles = {
                                    fileManagerViewModel.refresh()
                                    destination = SumiDestination.Files
                                }
                            )
                        }
                        is SumiDestination.Files -> {
                            FileManagerScreen(
                                viewModel = fileManagerViewModel,
                                onOpenFileInZenEditor = { file ->
                                    destination = SumiDestination.ZenEditor(file)
                                },
                                onOpenDirInTerminal = { dir ->
                                    terminalViewModel.cdToDirectory(dir)
                                    destination = SumiDestination.Terminal
                                },
                                onBack = {
                                    destination = SumiDestination.Terminal
                                }
                            )
                        }
                        is SumiDestination.ZenEditor -> {
                            ZenEditorScreen(
                                file = dest.file,
                                onBack = {
                                    fileManagerViewModel.refresh()
                                    destination = SumiDestination.Files
                                },
                                onRunInTerminal = { file ->
                                    terminalViewModel.runScriptFile(file)
                                    destination = SumiDestination.Terminal
                                }
                            )
                        }
                    }
                }
            }

            // Abstract IPC Hook Alert Banner (from LocalServerSocket "dev.sumi.terminal.hook")
            currentAlert?.let { alert ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Sumi.Sumi2)
                        .border(
                            1.dp,
                            if (alert.isError) Sumi.Kurenai else Sumi.Kinari,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { app.alertDispatcher.dismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${alert.title}: ${alert.message}",
                        color = if (alert.isError) Sumi.Kurenai else Sumi.Paper,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "✕",
                        color = Sumi.Muted,
                        fontFamily = Sumi.Mono,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
