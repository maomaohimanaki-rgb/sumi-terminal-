package dev.sumi.terminal.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sumi.terminal.ui.components.AccessoryBar
import dev.sumi.terminal.ui.components.PasteConfirmDialog
import dev.sumi.terminal.ui.components.TabBar
import dev.sumi.terminal.ui.components.TerminalCanvas
import dev.sumi.terminal.ui.components.TerminalInputConnectionView
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onOpenFiles: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalWabiSabiPalette.current

    val sessions by viewModel.sessions.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val thermalState by viewModel.thermalState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val mods by viewModel.mods.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val pendingSafePaste by viewModel.pendingSafePaste.collectAsState()
    val showAppearance by viewModel.showAppearance.collectAsState()

    val activeSession = remember(sessions, activeSessionId) {
        sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()
    }

    var inputViewRef by remember { mutableStateOf<TerminalInputConnectionView?>(null) }

    // Keep screen on while terminal is visible if enabled
    DisposableEffect(settings.keepScreenOn) {
        val window = (context as? ComponentActivity)?.window
        if (settings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { }
    }

    fun copyToSystemClipboard(text: String) {
        if (text.isEmpty()) return
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Sumi Terminal", text))
            Toast.makeText(context, "Copiado (${text.length} chars)", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerSystemClipboardPaste() {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val itemText = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            } else {
                ""
            }
            if (itemText.isNotEmpty()) {
                viewModel.onPasteRequested(itemText)
            } else {
                Toast.makeText(context, "Área de transferência vazia", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .systemBarsPadding()
            .imePadding()
    ) {
        TabBar(
            sessions = sessions,
            activeSessionId = activeSession?.id,
            thermalState = thermalState,
            onSelectTab = viewModel::selectTab,
            onCloseTab = viewModel::closeTab,
            onNewTab = viewModel::createNewTab,
            onOpenFiles = onOpenFiles,
            onOpenAppearance = { viewModel.setAppearanceVisible(true) }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            AndroidView(
                factory = { ctx ->
                    TerminalInputConnectionView(
                        context = ctx,
                        onSendBytes = viewModel::sendBytes,
                        onKey = viewModel::sendKey,
                        onRawText = viewModel::sendRawText,
                        onInterceptPaste = viewModel::onPasteRequested
                    ).also { inputViewRef = it }
                },
                update = { view ->
                    inputViewRef = view
                    view.onSendBytes = viewModel::sendBytes
                    view.onKey = viewModel::sendKey
                    view.onRawText = viewModel::sendRawText
                    view.onInterceptPaste = viewModel::onPasteRequested
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
            )

            if (activeSession != null) {
                TerminalCanvas(
                    session = activeSession,
                    settings = settings,
                    selection = selection,
                    onSelectionChange = viewModel::updateSelection,
                    onSelectAll = viewModel::selectAll,
                    onCopySelection = ::copyToSystemClipboard,
                    onRequestPaste = ::triggerSystemClipboardPaste,
                    onRequestKeyboard = { inputViewRef?.openKeyboard() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AccessoryBar(
            mods = mods,
            hasSelection = selection != null,
            onToggleCtrl = viewModel::toggleCtrl,
            onToggleAlt = viewModel::toggleAlt,
            onToggleShift = viewModel::toggleShift,
            onKeyPress = { key ->
                inputViewRef?.resetImeState()
                viewModel.sendKey(key)
            },
            onSymbolPress = { symbol ->
                inputViewRef?.resetImeState()
                viewModel.sendRawText(symbol)
            },
            onCopySelection = {
                viewModel.extractCurrentSelection()?.let(::copyToSystemClipboard)
            },
            onPasteFromClipboard = ::triggerSystemClipboardPaste
        )
    }

    pendingSafePaste?.let { pasteText ->
        PasteConfirmDialog(
            text = pasteText,
            onConfirm = viewModel::confirmSafePaste,
            onDismiss = viewModel::dismissSafePaste
        )
    }

    if (showAppearance) {
        AppearancePanel(
            settings = settings,
            onSelectPalette = viewModel::setPalette,
            onSetBackgroundPath = viewModel::setBackgroundPath,
            onSetBrightness = viewModel::setBrightness,
            onSetFontSize = viewModel::setFontSize,
            onSetCursorStyle = viewModel::setCursorStyle,
            onSetAutoColors = viewModel::setAutoColors,
            onSetKeepScreenOn = viewModel::setKeepScreenOn,
            onDismiss = { viewModel.setAppearanceVisible(false) }
        )
    }
}
