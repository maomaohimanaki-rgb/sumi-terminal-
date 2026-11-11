package dev.sumi.terminal.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import dev.sumi.terminal.data.preferences.CursorStyle
import dev.sumi.terminal.data.preferences.TerminalSettings
import dev.sumi.terminal.service.TerminalSession
import dev.sumi.terminal.terminal.TerminalSelection
import dev.sumi.terminal.ui.theme.LocalWabiSabiPalette
import dev.sumi.terminal.ui.theme.Sumi
import java.io.File
import kotlin.math.hypot
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
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val renderTick by session.renderTick.collectAsState()
    val scrollOffset by session.scrollOffset.collectAsState()
    val currentSelection by rememberUpdatedState(selection)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)

    val normalTypeface = remember(context) {
        try {
            ResourcesCompat.getFont(context, com.example.R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        } catch (_: Throwable) {
            Typeface.MONOSPACE
        }
    }
    val boldTypeface = remember(normalTypeface) {
        Typeface.create(normalTypeface, Typeface.BOLD)
    }

    val textPaint = remember(normalTypeface, settings.fontSizeSp, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = normalTypeface
            textSize = with(density) { settings.fontSizeSp.sp.toPx() }
        }
    }

    val fontMetrics = textPaint.fontMetrics
    val cellWidthPx = remember(textPaint) {
        textPaint.measureText("M").coerceAtLeast(8f)
    }
    val cellHeightPx = remember(textPaint) {
        ((fontMetrics.descent - fontMetrics.ascent) * 1.18f).coerceAtLeast(14f)
    }
    val baselineOffsetPx = remember(textPaint, cellHeightPx) {
        val fontHeight = fontMetrics.descent - fontMetrics.ascent
        -fontMetrics.ascent + (cellHeightPx - fontHeight) / 2f
    }
    val padLeftPx = with(density) { 8.dp.toPx() }
    val padTopPx = with(density) { 8.dp.toPx() }

    var activeHandle by remember { mutableStateOf(DraggedHandle.NONE) }
    var lastSnappedCol by remember { mutableIntStateOf(-1) }

    val (linesSnapshot, cursorPos) = remember(renderTick, palette, settings.autoCommandColors, scrollOffset) {
        session.buffer.snapshotScreen(
            palette = palette,
            autoCommandColors = settings.autoCommandColors,
            scrollOffset = scrollOffset
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .onSizeChanged { sz ->
                if (sz.width > 0 && sz.height > 0) {
                    val availW = (sz.width - padLeftPx * 2f).coerceAtLeast(0f)
                    val availH = (sz.height - padTopPx * 2f).coerceAtLeast(0f)
                    val newCols = (availW / cellWidthPx).toInt().coerceIn(20, 240)
                    val newRows = (availH / cellHeightPx).toInt().coerceIn(6, 120)
                    session.requestResizeDebounced(newRows, newCols)
                }
            }
            .pointerInput(session.id, cellWidthPx, cellHeightPx) {
                detectTapGestures(
                    onTap = {
                        if (currentSelection != null) {
                            currentOnSelectionChange(null)
                        }
                        onRequestKeyboard()
                    },
                    onLongPress = { offset ->
                        val totalRows = session.buffer.rows.coerceAtLeast(1)
                        val totalCols = session.buffer.cols.coerceAtLeast(1)
                        val row = ((offset.y - padTopPx) / cellHeightPx).toInt().coerceIn(0, totalRows - 1)
                        val col = ((offset.x - padLeftPx) / cellWidthPx).toInt().coerceIn(0, totalCols - 1)
                        val lineText = session.buffer.getLineString(row)
                        val initialSel = TerminalSelection.selectWordAt(lineText, row, col)
                        if (settings.hapticFeedback) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        currentOnSelectionChange(initialSel)
                    }
                )
            }
            .pointerInput(session.id, cellWidthPx, cellHeightPx) {
                var scrollAccumulator = 0f
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val sel = currentSelection
                        if (sel != null) {
                            val norm = sel.normalized()
                            val startHandlePos = Offset(
                                x = padLeftPx + norm.startCol * cellWidthPx,
                                y = padTopPx + (norm.startRow + 1) * cellHeightPx
                            )
                            val endHandlePos = Offset(
                                x = padLeftPx + (norm.endCol + 1) * cellWidthPx,
                                y = padTopPx + (norm.endRow + 1) * cellHeightPx
                            )
                            val distStart = hypot(startOffset.x - startHandlePos.x, startOffset.y - startHandlePos.y)
                            val distEnd = hypot(startOffset.x - endHandlePos.x, startOffset.y - endHandlePos.y)
                            val hitRadius = cellHeightPx * 2.8f

                            if (distStart <= hitRadius || distEnd <= hitRadius) {
                                activeHandle = if (distStart <= distEnd) DraggedHandle.START else DraggedHandle.END
                                lastSnappedCol = -1
                                return@detectDragGestures
                            }
                        }
                        activeHandle = DraggedHandle.NONE
                        scrollAccumulator = 0f
                    },
                    onDragEnd = {
                        activeHandle = DraggedHandle.NONE
                        lastSnappedCol = -1
                        scrollAccumulator = 0f
                    },
                    onDragCancel = {
                        activeHandle = DraggedHandle.NONE
                        lastSnappedCol = -1
                        scrollAccumulator = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val sel = currentSelection
                        if (sel != null && activeHandle != DraggedHandle.NONE) {
                            val pos = change.position
                            val totalRows = session.buffer.rows.coerceAtLeast(1)
                            val totalCols = session.buffer.cols.coerceAtLeast(1)
                            val targetRow = ((pos.y - padTopPx) / cellHeightPx).toInt().coerceIn(0, totalRows - 1)
                            val rawColFloat = ((pos.x - padLeftPx) / cellWidthPx).coerceIn(0f, (totalCols - 1).toFloat())
                            val lineStr = session.buffer.getLineString(targetRow)

                            val isStart = (activeHandle == DraggedHandle.START)
                            val (snappedCol, didSnap) = TerminalSelection.snapColumnWithWordMagnet(
                                line = lineStr,
                                rawColFloat = rawColFloat,
                                isStartHandle = isStart
                            )
                            val finalCol = snappedCol.coerceIn(0, totalCols - 1)

                            if (didSnap && finalCol != lastSnappedCol && settings.hapticFeedback) {
                                lastSnappedCol = finalCol
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }

                            val norm = sel.normalized()
                            val updated = if (isStart) {
                                TerminalSelection(
                                    startRow = targetRow,
                                    startCol = finalCol,
                                    endRow = norm.endRow,
                                    endCol = norm.endCol
                                ).normalized()
                            } else {
                                TerminalSelection(
                                    startRow = norm.startRow,
                                    startCol = norm.startCol,
                                    endRow = targetRow,
                                    endCol = finalCol
                                ).normalized()
                            }
                            currentOnSelectionChange(updated)
                        } else {
                            // Vertical scroll: dragging down (dragAmount.y > 0) pulls past history into view (increases scrollOffset)
                            // dragging up (dragAmount.y < 0) moves back toward live prompt (decreases scrollOffset)
                            scrollAccumulator += dragAmount.y
                            val cellH = cellHeightPx.coerceAtLeast(1f)
                            val linesDelta = (scrollAccumulator / cellH).toInt()
                            if (linesDelta != 0) {
                                session.scrollBy(linesDelta)
                                scrollAccumulator -= linesDelta * cellH
                            }
                        }
                    }
                )
            }
            .testTag("terminal_canvas")
    ) {
        // Optional user wallpaper with brightness overlay
        settings.customBackgroundPath?.let { bgPath ->
            val bgFile = remember(bgPath) { File(bgPath) }
            if (bgFile.exists()) {
                AsyncImage(
                    model = bgFile,
                    contentDescription = "Terminal Background",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.background.copy(alpha = (1f - settings.backgroundBrightness).coerceIn(0.2f, 0.96f)))
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

            val normSel = currentSelection?.normalized()

            // 1. Draw selection background
            if (normSel != null) {
                val maxRow = (linesSnapshot.size - 1).coerceAtMost(normSel.endRow)
                for (r in normSel.startRow..maxRow) {
                    if (r < 0) continue
                    val yTop = padTopPx + r * cellHeightPx
                    if (yTop >= canvasHeight || yTop + cellHeightPx <= 0f) continue

                    val lineCols = linesSnapshot.getOrNull(r)?.size ?: continue
                    val cStart = if (r == normSel.startRow) normSel.startCol.coerceIn(0, (lineCols - 1).coerceAtLeast(0)) else 0
                    val cEnd = if (r == normSel.endRow) normSel.endCol.coerceIn(0, (lineCols - 1).coerceAtLeast(0)) else (lineCols - 1).coerceAtLeast(0)
                    if (cEnd >= cStart) {
                        val xLeft = padLeftPx + cStart * cellWidthPx
                        val widthPx = (cEnd - cStart + 1) * cellWidthPx
                        if (xLeft < canvasWidth && xLeft + widthPx > 0f) {
                            drawRect(
                                color = palette.selectionBackground,
                                topLeft = Offset(xLeft, yTop),
                                size = Size(widthPx.coerceAtLeast(0f), cellHeightPx.coerceAtLeast(0f))
                            )
                        }
                    }
                }
            }

            // 2. Draw cell backgrounds
            linesSnapshot.forEachIndexed { r, cells ->
                val yTop = padTopPx + r * cellHeightPx
                if (yTop >= canvasHeight || yTop + cellHeightPx <= 0f) return@forEachIndexed

                cells.forEachIndexed { c, cell ->
                    cell.bgColor?.let { bg ->
                        val xLeft = padLeftPx + c * cellWidthPx
                        if (xLeft < canvasWidth && xLeft + cellWidthPx > 0f) {
                            drawRect(
                                color = bg,
                                topLeft = Offset(xLeft, yTop),
                                size = Size(cellWidthPx.coerceAtLeast(0f), cellHeightPx.coerceAtLeast(0f))
                            )
                        }
                    }
                }
            }

            // 2b. Draw text via Android Native Canvas (never throws Constraints exception!)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val defaultFgArgb = palette.foreground.toArgb()

                linesSnapshot.forEachIndexed { r, cells ->
                    val yTop = padTopPx + r * cellHeightPx
                    if (yTop >= canvasHeight || yTop + cellHeightPx <= 0f) return@forEachIndexed
                    val yBaseline = yTop + baselineOffsetPx

                    var idx = 0
                    val lineCols = cells.size
                    while (idx < lineCols) {
                        val first = cells[idx]
                        var runEnd = idx + 1
                        while (
                            runEnd < lineCols &&
                            cells[runEnd].fgColor == first.fgColor &&
                            cells[runEnd].bold == first.bold &&
                            cells[runEnd].underline == first.underline
                        ) {
                            runEnd++
                        }

                        val count = runEnd - idx
                        val xLeft = padLeftPx + idx * cellWidthPx
                        if (xLeft < canvasWidth && xLeft + count * cellWidthPx > 0f) {
                            textPaint.color = first.fgColor?.toArgb() ?: defaultFgArgb
                            textPaint.typeface = if (first.bold) boldTypeface else normalTypeface
                            textPaint.isUnderlineText = first.underline

                            val chars = CharArray(count) { k -> cells[idx + k].char }
                            native.drawText(chars, 0, count, xLeft, yBaseline, textPaint)
                        }
                        idx = runEnd
                    }
                }
            }

            // 3. Draw cursor
            val (curRow, curCol) = cursorPos
            val curX = padLeftPx + curCol * cellWidthPx
            val curY = padTopPx + curRow * cellHeightPx
            if (curY < canvasHeight && curY + cellHeightPx > 0f && curX < canvasWidth && curX + cellWidthPx > 0f) {
                when (settings.cursorStyle) {
                    CursorStyle.BLOCK -> {
                        drawRect(
                            color = palette.cursorColor.copy(alpha = 0.72f),
                            topLeft = Offset(curX, curY),
                            size = Size(cellWidthPx.coerceAtLeast(0f), cellHeightPx.coerceAtLeast(0f))
                        )
                    }
                    CursorStyle.UNDERLINE -> {
                        val h = (cellHeightPx * 0.16f).coerceAtLeast(2.5f)
                        drawRect(
                            color = palette.cursorColor,
                            topLeft = Offset(curX, curY + cellHeightPx - h),
                            size = Size(cellWidthPx.coerceAtLeast(0f), h)
                        )
                    }
                    CursorStyle.BEAM -> {
                        val w = (cellWidthPx * 0.22f).coerceAtLeast(2.5f)
                        drawRect(
                            color = palette.cursorColor,
                            topLeft = Offset(curX, curY),
                            size = Size(w, cellHeightPx.coerceAtLeast(0f))
                        )
                    }
                }
            }

            // 4. Draw dual selection teardown handles (Start and End)
            if (normSel != null) {
                val handleRadius = (cellHeightPx * 0.36f).coerceIn(7f, 16f)
                val startX = padLeftPx + normSel.startCol * cellWidthPx
                val startY = padTopPx + (normSel.startRow + 1) * cellHeightPx
                val endX = padLeftPx + (normSel.endCol + 1) * cellWidthPx
                val endY = padTopPx + (normSel.endRow + 1) * cellHeightPx

                // Left / Start teardrop handle
                if (startY in -handleRadius * 2f..(canvasHeight + handleRadius * 2f)) {
                    val startPath = Path().apply {
                        moveTo(startX, startY)
                        lineTo(startX - handleRadius, startY + handleRadius)
                        lineTo(startX, startY + handleRadius * 2f)
                        close()
                    }
                    drawPath(startPath, color = Sumi.Kinari)
                    drawCircle(
                        color = Sumi.Kinari,
                        radius = handleRadius * 0.78f,
                        center = Offset(startX - handleRadius * 0.5f, startY + handleRadius * 1.25f)
                    )
                }

                // Right / End teardrop handle
                if (endY in -handleRadius * 2f..(canvasHeight + handleRadius * 2f)) {
                    val endPath = Path().apply {
                        moveTo(endX, endY)
                        lineTo(endX + handleRadius, endY + handleRadius)
                        lineTo(endX, endY + handleRadius * 2f)
                        close()
                    }
                    drawPath(endPath, color = Sumi.Kinari)
                    drawCircle(
                        color = Sumi.Kinari,
                        radius = handleRadius * 0.78f,
                        center = Offset(endX + handleRadius * 0.5f, endY + handleRadius * 1.25f)
                    )
                }
            }
        }

        // Floating Action Pill when selection is active: Copiar | Selecionar tudo | Colar | Limpar
        selection?.normalized()?.let { normSel ->
            val pillRow = (normSel.startRow - 1).coerceAtLeast(0)
            val pillYOffsetPx = (padTopPx + pillRow * cellHeightPx - cellHeightPx * 0.8f)
                .roundToInt()
                .coerceAtLeast(8)
            val maxPillY = (session.buffer.rows * cellHeightPx).roundToInt().coerceAtLeast(8)
            val safePillY = pillYOffsetPx.coerceIn(8, maxPillY)

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, safePillY) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Sumi.Sumi2)
                    .border(1.dp, Sumi.Kinari.copy(alpha = 0.75f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("selection_action_pill"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Copiar",
                    color = Sumi.Kinari,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable {
                            val txt = session.buffer.extract(normSel)
                            if (txt.isNotEmpty()) {
                                onCopySelection(txt)
                            }
                            onSelectionChange(null)
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("pill_copy_button")
                )
                Text(
                    text = "•",
                    color = Sumi.Hairline,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
                Text(
                    text = "Selecionar tudo",
                    color = Sumi.Paper,
                    fontFamily = Sumi.Mono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onSelectAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("pill_select_all_button")
                )
                Text(
                    text = "•",
                    color = Sumi.Hairline,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp
                )
                Text(
                    text = "Colar",
                    color = Sumi.Shironeri,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable {
                            onSelectionChange(null)
                            onRequestPaste()
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("pill_paste_button")
                )
                Text(
                    text = "✕",
                    color = Sumi.Muted,
                    fontFamily = Sumi.Mono,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onSelectionChange(null) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("pill_clear_button")
                )
            }
        }

        // Floating scrollback badge when viewing history
        if (scrollOffset > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Sumi.Sumi2.copy(alpha = 0.92f))
                    .border(BorderStroke(1.dp, Sumi.Kinari.copy(alpha = 0.7f)), RoundedCornerShape(12.dp))
                    .clickable { session.scrollToBottom() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("scroll_to_bottom_badge")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "▼ Fim ($scrollOffset)",
                        color = Sumi.Kinari,
                        fontFamily = Sumi.Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
