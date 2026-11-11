package dev.sumi.terminal.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.R
import dev.sumi.terminal.data.preferences.CursorStyle
import dev.sumi.terminal.data.preferences.TerminalSettings
import dev.sumi.terminal.service.TerminalSession
import dev.sumi.terminal.terminal.TerminalSelection
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    settings: TerminalSettings,
    selection: TerminalSelection?,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onCopySelection: (String) -> Unit,
    onRequestPaste: () -> Unit,
    onRequestKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalWabiSabiPalette.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val renderTick by session.renderTick.collectAsState()

    val baseTextStyle = remember(settings.fontSizeSp, palette.foreground) {
        TextStyle(
            fontFamily = Sumi.Mono,
            fontSize = settings.fontSizeSp.sp,
            color = palette.foreground
        )
    }

    // Measure single character cell geometry
    val charMetrics = remember(baseTextStyle) {
        val layout = textMeasurer.measure(AnnotatedString("M"), style = baseTextStyle)
        val w = layout.size.width.toFloat().coerceAtLeast(8f)
        val h = (layout.size.height.toFloat() * 1.22f).coerceAtLeast(14f)
        w to h
    }
    val cellWidthPx = charMetrics.first
    val cellHeightPx = charMetrics.second
    val padHorizontalPx = with(density) { 10.dp.toPx() }
    val padVerticalPx = with(density) { 8.dp.toPx() }

    // Smooth breathing cursor animation (6.2: Indicador de cursor respirando suavemente sem piscar agressivo)
    val infiniteTransition = rememberInfiniteTransition(label = "cursorBreath")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBreathAlpha"
    )

    // Track last snapped word boundary to fire HapticFeedbackType.TextHandleMove only on new snap
    var lastSnappedCol by remember { mutableStateOf(-1) }
    var lastSnappedRow by remember { mutableStateOf(-1) }

    val snapshot = remember(renderTick, palette, settings.autoCommandColors) {
        session.buffer.snapshotScreen(
            palette = palette,
            autoCommandColors = settings.autoCommandColors
        )
    }
    val lines = snapshot.first
    val cursorRow = snapshot.second.first
    val cursorCol = snapshot.second.second

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .onSizeChanged { intSize ->
                if (intSize.width > 0 && intSize.height > 0) {
                    val availW = (intSize.width - padHorizontalPx * 2).coerceAtLeast(cellWidthPx * 20)
                    val availH = (intSize.height - padVerticalPx * 2).coerceAtLeast(cellHeightPx * 6)
                    val newCols = floor(availW / cellWidthPx).toInt().coerceIn(24, 240)
                    val newRows = floor(availH / cellHeightPx).toInt().coerceIn(6, 120)
                    session.requestResizeDebounced(newRows, newCols)
                }
            }
            .pointerInput(cellWidthPx, cellHeightPx) {
                detectTapGestures(
                    onTap = {
                        if (selection != null) {
                            onSelectionChange(null)
                        } else {
                            onRequestKeyboard()
                        }
                    },
                    onDoubleTap = { offset ->
                        val row = ((offset.y - padVerticalPx) / cellHeightPx).toInt()
                            .coerceIn(0, session.buffer.rows - 1)
                        val col = ((offset.x - padHorizontalPx) / cellWidthPx).toInt()
                            .coerceIn(0, session.buffer.cols - 1)
                        val lineStr = session.buffer.getLineString(row)
                        val wordSel = TerminalSelection.selectWordAt(lineStr, row, col)
                        if (settings.hapticFeedback) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onSelectionChange(wordSel)
                    }
                )
            }
            .pointerInput(cellWidthPx, cellHeightPx, settings.hapticFeedback) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset ->
                        val row = ((startOffset.y - padVerticalPx) / cellHeightPx).toInt()
                            .coerceIn(0, session.buffer.rows - 1)
                        val col = ((startOffset.x - padHorizontalPx) / cellWidthPx).toInt()
                            .coerceIn(0, session.buffer.cols - 1)
                        val lineStr = session.buffer.getLineString(row)
                        val wordSel = TerminalSelection.selectWordAt(lineStr, row, col)
                        lastSnappedRow = row
                        lastSnappedCol = wordSel.endCol
                        if (settings.hapticFeedback) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onSelectionChange(wordSel)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val curSel = selection ?: return@detectDragGesturesAfterLongPress
                        val pos = change.position
                        val row = ((pos.y - padVerticalPx) / cellHeightPx).toInt()
                            .coerceIn(0, session.buffer.rows - 1)
                        val rawColFloat = ((pos.x - padHorizontalPx) / cellWidthPx)
                            .coerceIn(0f, (session.buffer.cols - 1).toFloat())
                        val lineStr = session.buffer.getLineString(row)

                        // Specification B: Hysteresis Word Magnet (1.8 cell radius attraction)
                        val isDraggingBeforeAnchor = row < curSel.startRow ||
                                (row == curSel.startRow && rawColFloat < curSel.startCol)
                        val (snappedCol, didSnap) = TerminalSelection.snapColumnWithWordMagnet(
                            line = lineStr,
                            rawColFloat = rawColFloat,
                            isStartHandle = isDraggingBeforeAnchor,
                            magnetThresholdCells = 1.8f
                        )

                        if (didSnap && (snappedCol != lastSnappedCol || row != lastSnappedRow)) {
                            lastSnappedCol = snappedCol
                            lastSnappedRow = row
                            if (settings.hapticFeedback) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }

                        onSelectionChange(
                            curSel.copy(
                                endRow = row,
                                endCol = snappedCol
                            )
                        )
                    }
                )
            }
            .testTag("terminal_canvas")
    ) {
        // Optional Wabi-Sabi Sumi-e background art or custom sandbox image
        val bgPath = settings.customBackgroundPath
        if (bgPath == "SUMI_ART") {
            Image(
                painter = painterResource(id = R.drawable.img_sumi_ink_bg_1794356389341),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha((settings.backgroundBrightness * 0.35f).coerceIn(0.05f, 0.75f))
            )
        } else if (!bgPath.isNullOrBlank() && bgPath != "NONE") {
            val file = remember(bgPath) { File(bgPath) }
            if (file.exists()) {
                Image(
                    painter = rememberAsyncImagePainter(model = file),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha((settings.backgroundBrightness * 0.45f).coerceIn(0.05f, 0.85f))
                )
            }
        }

        // Core VT100/Xterm Grid & Breathing Cursor Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val normSel = selection?.normalized()

            // 1. Draw Selection Highlight & Magnetic Handles
            if (normSel != null) {
                for (r in normSel.startRow..normSel.endRow.coerceAtMost(lines.lastIndex)) {
                    if (r < 0) continue
                    val cStart = if (r == normSel.startRow) normSel.startCol else 0
                    val cEnd = if (r == normSel.endRow) normSel.endCol else (session.buffer.cols - 1)
                    if (cEnd >= cStart) {
                        drawRect(
                            color = palette.selectionBackground.copy(alpha = 0.78f),
                            topLeft = Offset(
                                x = padHorizontalPx + cStart * cellWidthPx,
                                y = padVerticalPx + r * cellHeightPx
                            ),
                            size = Size(
                                width = (cEnd - cStart + 1) * cellWidthPx,
                                height = cellHeightPx
                            )
                        )
                    }
                }
            }

            // 2. Draw Terminal Rows
            lines.forEachIndexed { rowIdx, rowCells ->
                val yOffset = padVerticalPx + rowIdx * cellHeightPx

                // Draw explicit cell background colors (e.g., neofetch color blocks)
                rowCells.forEachIndexed { colIdx, cell ->
                    cell.bgColor?.let { bg ->
                        drawRect(
                            color = bg,
                            topLeft = Offset(
                                x = padHorizontalPx + colIdx * cellWidthPx,
                                y = yOffset
                            ),
                            size = Size(cellWidthPx + 0.5f, cellHeightPx)
                        )
                    }
                }

                // Check if row has non-space characters
                var lastNonEmpty = -1
                for (i in rowCells.indices.reversed()) {
                    if (rowCells[i].char != ' ') {
                        lastNonEmpty = i
                        break
                    }
                }

                if (lastNonEmpty >= 0) {
                    val annotatedRow = buildAnnotatedString {
                        for (cIdx in 0..lastNonEmpty) {
                            val cell = rowCells[cIdx]
                            val fg = cell.fgColor ?: palette.foreground
                            pushStyle(
                                SpanStyle(
                                    color = fg,
                                    fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                                    textDecoration = if (cell.underline) TextDecoration.Underline else TextDecoration.None
                                )
                            )
                            append(cell.char)
                            pop()
                        }
                    }

                    drawText(
                        textMeasurer = textMeasurer,
                        text = annotatedRow,
                        style = baseTextStyle,
                        topLeft = Offset(padHorizontalPx, yOffset)
                    )
                }
            }

            // 3. Draw Breathing Cursor (Block, Beam, or Underline)
            val cursorX = padHorizontalPx + cursorCol * cellWidthPx
            val cursorY = padVerticalPx + cursorRow * cellHeightPx
            val cColor = palette.cursorColor.copy(alpha = cursorAlpha)

            when (settings.cursorStyle) {
                CursorStyle.BLOCK -> {
                    drawRect(
                        color = cColor,
                        topLeft = Offset(cursorX, cursorY),
                        size = Size(cellWidthPx, cellHeightPx)
                    )
                }
                CursorStyle.BEAM -> {
                    drawRect(
                        color = cColor,
                        topLeft = Offset(cursorX, cursorY),
                        size = Size(2.5.dp.toPx(), cellHeightPx)
                    )
                }
                CursorStyle.UNDERLINE -> {
                    drawRect(
                        color = cColor,
                        topLeft = Offset(cursorX, cursorY + cellHeightPx - 3.dp.toPx()),
                        size = Size(cellWidthPx, 3.dp.toPx())
                    )
                }
            }

            // 4. Draw Magnetic Selection Handles (Kinari gold anchors)
            if (normSel != null) {
                val startHandleX = padHorizontalPx + normSel.startCol * cellWidthPx
                val startHandleY = padVerticalPx + (normSel.startRow + 1) * cellHeightPx
                val endHandleX = padHorizontalPx + (normSel.endCol + 1) * cellWidthPx
                val endHandleY = padVerticalPx + (normSel.endRow + 1) * cellHeightPx

                drawCircle(
                    color = Sumi.Kinari,
                    radius = 5.dp.toPx(),
                    center = Offset(startHandleX, startHandleY + 4.dp.toPx())
                )
                drawCircle(
                    color = Sumi.Kinari,
                    radius = 5.dp.toPx(),
                    center = Offset(endHandleX, endHandleY + 4.dp.toPx())
                )
            }
        }

        // Floating Action Pill when Word Magnet selection is active
        if (selection != null) {
            val norm = selection.normalized()
            val pillOffsetY = ((padVerticalPx + (norm.startRow - 1).coerceAtLeast(0) * cellHeightPx)
                .roundToInt() - 40).coerceAtLeast(12)

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, pillOffsetY) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Sumi.Sumi2)
                    .border(1.dp, Sumi.Kinari.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("word_magnet_selection_toolbar"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ÍMÃ DE PALAVRAS",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 10.sp
                )
                Text(
                    text = "Copiar",
                    color = Sumi.Kinari,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        val txt = session.buffer.extractSelectedText(selection)
                        onCopySelection(txt)
                        onSelectionChange(null)
                    }
                )
                Text(
                    text = "Colar",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        onSelectionChange(null)
                        onRequestPaste()
                    }
                )
                Text(
                    text = "✕",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onSelectionChange(null) }
                )
            }
        }
    }
}
