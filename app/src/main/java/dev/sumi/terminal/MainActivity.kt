package dev.sumi.terminal

import android.content.Intent
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.sumi.terminal.service.TerminalDaemonService
import dev.sumi.terminal.ui.editor.ZenEditorScreen
import dev.sumi.terminal.ui.files.FileManagerScreen
import dev.sumi.terminal.ui.files.FileManagerViewModel
import dev.sumi.terminal.ui.setup.SetupScreen
import dev.sumi.terminal.ui.terminal.TerminalScreen
import dev.sumi.terminal.ui.terminal.TerminalViewModel
import dev.sumi.terminal.ui.theme.SumiPalettes
import dev.sumi.terminal.ui.theme.SumiTheme
import java.io.File

sealed interface SumiDestination {
    data object Terminal : SumiDestination
    data object Files : SumiDestination
    data class ZenEditor(val file: File) : SumiDestination
}

@Composable
fun SumiRootApp(app: SumiApp) {
    val context = LocalContext.current
    val installState by app.rootfsInstaller.state.collectAsState()

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

    LaunchedEffect(Unit) {
        app.rootfsInstaller.ensureInstalled()
        runCatching {
            val svcIntent = Intent(context, TerminalDaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        }
    }

    SumiTheme(palette = activePalette) {
        if (!installState.isComplete) {
            SetupScreen(state = installState)
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
    }
}
