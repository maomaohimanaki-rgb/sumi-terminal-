package dev.sumi.terminal.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
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
    val composeClipboard = LocalClipboardManager.current
    val hostView = LocalView.current
    val palette = LocalWabiSabiPalette.current

    val sessions by viewModel.sessions.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val thermalState by viewModel.thermalState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val mods by viewModel.mods.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val pendingSafePaste by viewModel.pendingSafePaste.collectAsState()
    val showAppearance by viewModel.showAppearance.collectAsState()

    var inputViewRef by remember { mutableStateOf<TerminalInputConnectionView?>(null) }

    DisposableEffect(settings.keepScreenOn) {
        hostView.keepScreenOn = settings.keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }

    val activeSession = remember(sessions, activeSessionId) {
        sessions.firstOrNull { it.id == activeSessionId } ?: sessions.firstOrNull()
    }

    fun copyToSystemClipboard(text: String) {
        if (text.isEmpty()) return
        composeClipboard.setText(AnnotatedString(text))
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Sumi Terminal", text))
    }

    fun triggerSystemClipboardPaste() {
        val fromCompose = composeClipboard.getText()?.text
        if (!fromCompose.isNullOrEmpty()) {
            viewModel.onPasteRequested(fromCompose)
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!clipText.isNullOrEmpty()) {
            viewModel.onPasteRequested(clipText)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .testTag("terminal_screen")
    ) {
        TabBar(
            sessions = sessions,
            activeSessionId = activeSession?.id,
            thermalState = thermalState,
            onSelectTab = viewModel::selectTab,
            onNewTab = viewModel::createNewTab,
            onCloseTab = viewModel::closeTab,
            onOpenFiles = onOpenFiles,
            onOpenAppearance = { viewModel.setAppearanceVisible(true) }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Invisible native View with custom BaseInputConnection preserving Gboard/Samsung clipboard bar
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
