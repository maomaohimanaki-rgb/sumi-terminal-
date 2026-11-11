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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private enum class DraggedHandle {
    NONE, START, END
}

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    settings: TerminalSettings,
    selection: TerminalSelection?,
    onSelectionChange: (TerminalSelection?) -> Unit,
    onSelectAll: () -> Unit,
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

    // Always keep live references so pointerInput coroutines never read stale selection state
    val currentSelection by rememberUpdatedState(selection)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)

    val baseTextStyle = remember(settings.fontSizeSp, palette.foreground) {
        TextStyle(
            fontFamily = Sumi.Mono,
            fontSize = settings.fontSizeSp.sp,
            color = palette.foreground
        )
    }

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
    val handleDropOffsetPx = with(density) { 6.dp.toPx() }

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

    var lastSnappedCol by remember { mutableIntStateOf(-1) }
    var lastSnappedRow by remember { mutableIntStateOf(-1) }
    var activeHandle by remember { mutableStateOf(DraggedHandle.NONE) }
    var fixedAnchorRow by remember { mutableIntStateOf(0) }
    var fixedAnchorCol by remember { mutableIntStateOf(0) }
    var longPressOriginRow by remember { mutableIntStateOf(0) }
    var longPressOriginCol by remember { mutableIntStateOf(0) }

    val snapshot = remember(renderTick, palette, settings.autoCommandColors) {
        session.buffer.snapshotScreen(
            palette = palette,
            autoCommandColors = settings.autoCommandColors
        )
    }
    val lines = snapshot.first
    val cursorRow = snapshot.second.first
    val cursorCol = snapshot.second.second

    val hasActiveSelection = selection != null

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
            // 1. Tap & Double-Tap Mode: Double-tap selects complete word immediately
            .pointerInput(cellWidthPx, cellHeightPx, settings.hapticFeedback) {
                detectTapGestures(
                    onTap = {
                        if (currentSelection != null) {
                            currentOnSelectionChange(null)
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
                        currentOnSelectionChange(wordSel)
                    }
                )
            }
            // 2. Direct Handle Dragging when a selection is active: both LEFT (Start) and RIGHT (End) handles move freely
            .pointerInput(cellWidthPx, cellHeightPx, hasActiveSelection, settings.hapticFeedback) {
                if (!hasActiveSelection) return@pointerInput
                detectDragGestures(
                    onDragStart = { touch ->
                        val liveSel = currentSelection ?: return@detectDragGestures
                        val norm = liveSel.normalized()

                        val startHandleDrop = Offset(
                            x = padHorizontalPx + norm.startCol * cellWidthPx,
                            y = padVerticalPx + (norm.startRow + 1) * cellHeightPx + handleDropOffsetPx
                        )
                        val startCellCenter = Offset(
                            x = padHorizontalPx + (norm.startCol + 0.5f) * cellWidthPx,
                            y = padVerticalPx + (norm.startRow + 0.5f) * cellHeightPx
                        )
                        val endHandleDrop = Offset(
                            x = padHorizontalPx + (norm.endCol + 1) * cellWidthPx,
                            y = padVerticalPx + (norm.endRow + 1) * cellHeightPx + handleDropOffsetPx
                        )
                        val endCellCenter = Offset(
                            x = padHorizontalPx + (norm.endCol + 0.5f) * cellWidthPx,
                            y = padVerticalPx + (norm.endRow + 0.5f) * cellHeightPx
                        )

                        val distStart = min(
                            hypot(touch.x - startHandleDrop.x, touch.y - startHandleDrop.y),
                            hypot(touch.x - startCellCenter.x, touch.y - startCellCenter.y)
                        )
                        val distEnd = min(
                            hypot(touch.x - endHandleDrop.x, touch.y - endHandleDrop.y),
                            hypot(touch.x - endCellCenter.x, touch.y - endCellCenter.y)
                        )

                        if (distStart <= distEnd) {
                            activeHandle = DraggedHandle.START
                            fixedAnchorRow = norm.endRow
                            fixedAnchorCol = norm.endCol
                            lastSnappedRow = norm.startRow
                            lastSnappedCol = norm.startCol
                        } else {
                            activeHandle = DraggedHandle.END
                            fixedAnchorRow = norm.startRow
                            fixedAnchorCol = norm.startCol
                            lastSnappedRow = norm.endRow
                            lastSnappedCol = norm.endCol
                        }
                    },
                    onDrag = { change, _ ->
                        if (activeHandle == DraggedHandle.NONE) return@detectDragGestures
                        change.consume()
                        val pos = change.position
                        val row = ((pos.y - padVerticalPx) / cellHeightPx).toInt()
                            .coerceIn(0, session.buffer.rows - 1)
                        val rawColFloat = ((pos.x - padHorizontalPx) / cellWidthPx)
                            .coerceIn(0f, (session.buffer.cols - 1).toFloat())
                        val lineStr = session.buffer.getLineString(row)

                        val isStart = activeHandle == DraggedHandle.START
                        val (snappedCol, didSnap) = TerminalSelection.snapColumnWithWordMagnet(
                            line = lineStr,
                            rawColFloat = rawColFloat,
                            isStartHandle = isStart,
                            magnetThresholdCells = 0.42f
                        )

                        if (didSnap && (snappedCol != lastSnappedCol || row != lastSnappedRow)) {
                            lastSnappedCol = snappedCol
                            lastSnappedRow = row
                            if (settings.hapticFeedback) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }

                        val updated = if (isStart) {
                            TerminalSelection(
                                startRow = row,
                                startCol = snappedCol,
                                endRow = fixedAnchorRow,
                                endCol = fixedAnchorCol
                            )
                        } else {
                            TerminalSelection(
                                startRow = fixedAnchorRow,
                                startCol = fixedAnchorCol,
                                endRow = row,
                                endCol = snappedCol
                            )
                        }
                        currentOnSelectionChange(updated)
                    },
                    onDragEnd = { activeHandle = DraggedHandle.NONE },
                    onDragCancel = { activeHandle = DraggedHandle.NONE }
                )
            }
            // 3. Long-Press & Continuous Drag: starts at touched cell and expands freely across words & lines
            .pointerInput(cellWidthPx, cellHeightPx, settings.hapticFeedback) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startOffset ->
                        val row = ((startOffset.y - padVerticalPx) / cellHeightPx).toInt()
                            .coerceIn(0, session.buffer.rows - 1)
                        val col = ((startOffset.x - padHorizontalPx) / cellWidthPx).toInt()
                            .coerceIn(0, session.buffer.cols - 1)
                        longPressOriginRow = row
                        longPressOriginCol = col
                        lastSnappedRow = row
                        lastSnappedCol = col
                        if (settings.hapticFeedback) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        val lineStr = session.buffer.getLineString(row)
                        val initialSel = TerminalSelection.selectWordAt(lineStr, row, col)
                        longPressOriginRow = initialSel.startRow
                        longPressOriginCol = initialSel.startCol
                        currentOnSelectionChange(initialSel)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val pos = change.position
                        val row = ((pos.y - padVerticalPx) / cellHeightPx).toInt()
                            .coerceIn(0, session.buffer.rows - 1)
                        val rawColFloat = ((pos.x - padHorizontalPx) / cellWidthPx)
                            .coerceIn(0f, (session.buffer.cols - 1).toFloat())
                        val lineStr = session.buffer.getLineString(row)

                        val isDraggingBackwards = row < longPressOriginRow ||
                                (row == longPressOriginRow && rawColFloat < longPressOriginCol)
                        val (snappedCol, didSnap) = TerminalSelection.snapColumnWithWordMagnet(
                            line = lineStr,
                            rawColFloat = rawColFloat,
                            isStartHandle = isDraggingBackwards,
                            magnetThresholdCells = 0.42f
                        )

                        if (didSnap && (snappedCol != lastSnappedCol || row != lastSnappedRow)) {
                            lastSnappedCol = snappedCol
                            lastSnappedRow = row
                            if (settings.hapticFeedback) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }

                        currentOnSelectionChange(
                            TerminalSelection(
                                startRow = longPressOriginRow,
                                startCol = longPressOriginCol,
                                endRow = row,
                                endCol = snappedCol
                            )
                        )
                    }
                )
            }
            .testTag("terminal_canvas")
    ) {
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            val normSel = selection?.normalized()

            // 1. Draw Selection Highlight
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

            // 3. Draw Breathing Cursor
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

            // 4. Draw Selection Handles (Start & End Kinari stems + droplets)
            if (normSel != null) {
                val startHandleX = padHorizontalPx + normSel.startCol * cellWidthPx
                val startTopY = padVerticalPx + normSel.startRow * cellHeightPx
                val startBottomY = padVerticalPx + (normSel.startRow + 1) * cellHeightPx

                val endHandleX = padHorizontalPx + (normSel.endCol + 1) * cellWidthPx
                val endTopY = padVerticalPx + normSel.endRow * cellHeightPx
                val endBottomY = padVerticalPx + (normSel.endRow + 1) * cellHeightPx

                // Start vertical bar + droplet
                drawLine(
                    color = Sumi.Kinari,
                    start = Offset(startHandleX, startTopY),
                    end = Offset(startHandleX, startBottomY + 5.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = Sumi.Kinari,
                    radius = 7.dp.toPx(),
                    center = Offset(startHandleX, startBottomY + 6.dp.toPx())
                )

                // End vertical bar + droplet
                drawLine(
                    color = Sumi.Kinari,
                    start = Offset(endHandleX, endTopY),
                    end = Offset(endHandleX, endBottomY + 5.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = Sumi.Kinari,
                    radius = 7.dp.toPx(),
                    center = Offset(endHandleX, endBottomY + 6.dp.toPx())
                )
            }
        }

        // Clean minimalist Selection Action Bar: Copiar | Selecionar tudo | Colar | ✕
        if (selection != null) {
            val norm = selection.normalized()
            val pillOffsetY = ((padVerticalPx + (norm.startRow - 1).coerceAtLeast(0) * cellHeightPx)
                .roundToInt() - 40).coerceAtLeast(12)

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, pillOffsetY) }
                    .clip(RoundedCornerShape(18.dp))
                    .background(Sumi.Sumi2)
                    .border(1.dp, Sumi.Kinari.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .testTag("selection_action_toolbar"),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Copiar",
                    color = Sumi.Kinari,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            val txt = session.buffer.extract(selection)
                            onCopySelection(txt)
                            onSelectionChange(null)
                        }
                        .testTag("selection_copy_button")
                )
                Text(
                    text = "Selecionar tudo",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            onSelectAll()
                        }
                        .testTag("selection_select_all_button")
                )
                Text(
                    text = "Colar",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable {
                            onSelectionChange(null)
                            onRequestPaste()
                        }
                        .testTag("selection_paste_button")
                )
                Text(
                    text = "✕",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onSelectionChange(null) }
                        .testTag("selection_clear_button")
                )
            }
        }
    }
}
