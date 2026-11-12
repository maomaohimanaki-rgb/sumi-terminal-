package dev.sumi.terminal.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
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
import kotlin.math.abs
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
    val currentScrollOffset by rememberUpdatedState(scrollOffset)
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
        (textPaint.textSize * 1.125f).coerceAtLeast(12f)
    }
    val baselineOffsetPx = remember(textPaint, cellHeightPx) {
        val fontHeight = fontMetrics.descent - fontMetrics.ascent
        -fontMetrics.ascent + (cellHeightPx - fontHeight) / 2f
    }
    val padLeftPx = with(density) { 8.dp.toPx() }
    val padTopPx = with(density) { 8.dp.toPx() }

    var activeHandle by remember { mutableStateOf(DraggedHandle.NONE) }
    var lastSnappedCol by remember { mutableIntStateOf(-1) }
    var canvasWidthPx by remember { mutableIntStateOf(0) }
    var canvasHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(session.id, canvasWidthPx, canvasHeightPx, cellWidthPx, cellHeightPx) {
        if (canvasWidthPx > 0 && canvasHeightPx > 0) {
            val availW = (canvasWidthPx - padLeftPx * 2f).coerceAtLeast(0f)
            val availH = (canvasHeightPx - padTopPx * 2f).coerceAtLeast(0f)
            val newCols = (availW / cellWidthPx).toInt().coerceIn(20, 240)
            val newRows = (availH / cellHeightPx).toInt().coerceIn(6, 120)
            session.requestResizeDebounced(newRows, newCols)
        }
    }

    val (linesSnapshot, cursorPos) = remember(renderTick, palette, settings.autoCommandColors, scrollOffset) {
        session.buffer.snapshotScreen(
            palette = palette,
            autoCommandColors = settings.autoCommandColors,
            scrollOffset = scrollOffset
        )
    }

    val coroutineScope = rememberCoroutineScope()
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var pillManualOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(selection) {
        if (selection == null) {
            pillManualOffset = Offset.Zero
            activeHandle = DraggedHandle.NONE
            autoScrollJob?.cancel()
            autoScrollJob = null
        }
    }

    LaunchedEffect(activeHandle) {
        if (activeHandle == DraggedHandle.NONE) {
            autoScrollJob?.cancel()
            autoScrollJob = null
        }
    }

    DisposableEffect(session.id) {
        onDispose {
            autoScrollJob?.cancel()
            autoScrollJob = null
            flingJob?.cancel()
            flingJob = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .onSizeChanged { sz ->
                if (sz.width > 0 && sz.height > 0) {
                    canvasWidthPx = sz.width
                    canvasHeightPx = sz.height
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
                        flingJob?.cancel()
                        flingJob = null
                        autoScrollJob?.cancel()
                        autoScrollJob = null
                        if (currentSelection != null) {
                            currentOnSelectionChange(null)
                        }
                        onRequestKeyboard()
                    },
                    onLongPress = { offset ->
                        flingJob?.cancel()
                        flingJob = null
                        autoScrollJob?.cancel()
                        autoScrollJob = null
                        val totalRows = session.buffer.rows.coerceAtLeast(1)
                        val totalCols = session.buffer.cols.coerceAtLeast(1)
                        val touchRow = ((offset.y - padTopPx) / cellHeightPx).toInt().coerceIn(0, totalRows - 1)
                        val col = ((offset.x - padLeftPx) / cellWidthPx).toInt().coerceIn(0, totalCols - 1)

                        // Absolute row: (totalLines - rows - scrollOffset) + touchRow
                        val totalLines = session.buffer.totalLines
                        val firstRow = (totalLines - totalRows - currentScrollOffset).coerceAtLeast(0)
                        val absRow = (firstRow + touchRow).coerceIn(0, (totalLines - 1).coerceAtLeast(0))

                        val lineText = session.buffer.getLineString(absRow)
                        val initialSel = TerminalSelection.selectWordAt(lineText, absRow, col)
                        if (settings.hapticFeedback) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        pillManualOffset = Offset.Zero
                        currentOnSelectionChange(initialSel)
                    }
                )
            }
            .pointerInput(session.id, cellWidthPx, cellHeightPx) {
                var scrollAccumulator = 0f
                val velocityTracker = VelocityTracker()

                detectDragGestures(
                    onDragStart = { startOffset ->
                        autoScrollJob?.cancel()
                        autoScrollJob = null
                        flingJob?.cancel()
                        flingJob = null
                        velocityTracker.resetTracking()

                        val sel = currentSelection
                        if (sel != null) {
                            val norm = sel.normalized()
                            val totalRows = session.buffer.rows.coerceAtLeast(1)
                            val totalLines = session.buffer.totalLines
                            val firstRow = (totalLines - totalRows - session.scrollOffset.value).coerceAtLeast(0)

                            val startRelRow = norm.startRow - firstRow
                            val endRelRow = norm.endRow - firstRow

                            val startHandlePos = Offset(
                                x = padLeftPx + norm.startCol * cellWidthPx,
                                y = padTopPx + (startRelRow + 1) * cellHeightPx
                            )
                            val endHandlePos = Offset(
                                x = padLeftPx + (norm.endCol + 1) * cellWidthPx,
                                y = padTopPx + (endRelRow + 1) * cellHeightPx
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
                        val wasDraggingHandle = activeHandle != DraggedHandle.NONE
                        activeHandle = DraggedHandle.NONE
                        lastSnappedCol = -1
                        scrollAccumulator = 0f
                        autoScrollJob?.cancel()
                        autoScrollJob = null

                        if (!wasDraggingHandle) {
                            val velocityY = velocityTracker.calculateVelocity().y
                            if (abs(velocityY) > 150f) {
                                flingJob?.cancel()
                                flingJob = coroutineScope.launch {
                                    var lastValue = 0f
                                    var flingAccumulator = 0f
                                    val cellH = cellHeightPx.coerceAtLeast(1f)
                                    Animatable(0f).animateDecay(
                                        initialVelocity = velocityY,
                                        animationSpec = exponentialDecay(frictionMultiplier = 1.75f)
                                    ) {
                                        val delta = value - lastValue
                                        lastValue = value
                                        flingAccumulator += delta
                                        val lines = (flingAccumulator / cellH).toInt()
                                        if (lines != 0) {
                                            session.scrollBy(lines)
                                            flingAccumulator -= lines * cellH
                                        }
                                    }
                                }
                            }
                        }
                        velocityTracker.resetTracking()
                    },
                    onDragCancel = {
                        activeHandle = DraggedHandle.NONE
                        lastSnappedCol = -1
                        scrollAccumulator = 0f
                        autoScrollJob?.cancel()
                        autoScrollJob = null
                        velocityTracker.resetTracking()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val sel = currentSelection
                        if (sel != null && activeHandle != DraggedHandle.NONE) {
                            if (!change.pressed) {
                                autoScrollJob?.cancel()
                                autoScrollJob = null
                                activeHandle = DraggedHandle.NONE
                                return@detectDragGestures
                            }

                            val pos = change.position
                            val totalRows = session.buffer.rows.coerceAtLeast(1)
                            val totalCols = session.buffer.cols.coerceAtLeast(1)
                            val totalLines = session.buffer.totalLines
                            val firstRow = (totalLines - totalRows - session.scrollOffset.value).coerceAtLeast(0)

                            val touchRelRow = ((pos.y - padTopPx) / cellHeightPx).toInt().coerceIn(0, totalRows - 1)
                            val targetAbsRow = (firstRow + touchRelRow).coerceIn(0, (totalLines - 1).coerceAtLeast(0))
                            val rawColFloat = ((pos.x - padLeftPx) / cellWidthPx).coerceIn(0f, (totalCols - 1).toFloat())
                            val lineStr = session.buffer.getLineString(targetAbsRow)

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
                                    startRow = targetAbsRow,
                                    startCol = finalCol,
                                    endRow = norm.endRow,
                                    endCol = norm.endCol
                                ).normalized()
                            } else {
                                TerminalSelection(
                                    startRow = norm.startRow,
                                    startCol = norm.startCol,
                                    endRow = targetAbsRow,
                                    endCol = finalCol
                                ).normalized()
                            }
                            currentOnSelectionChange(updated)

                            // Continuous auto-scroll when dragging handle near edge - strictly requires pointer to remain pressed
                            val topThreshold = padTopPx + cellHeightPx * 1.5f
                            val bottomThreshold = (size.height - cellHeightPx * 1.5f).coerceAtLeast(topThreshold + cellHeightPx)
                            val isNearTop = pos.y < topThreshold
                            val isNearBottom = pos.y > bottomThreshold

                            if (change.pressed && (isNearTop || isNearBottom)) {
                                if (autoScrollJob == null || autoScrollJob?.isActive == false) {
                                    val direction = if (isNearTop) 1 else -1
                                    autoScrollJob = coroutineScope.launch {
                                        while (isActive && activeHandle != DraggedHandle.NONE) {
                                            delay(65L)
                                            if (activeHandle == DraggedHandle.NONE) break
                                            session.scrollBy(direction)
                                            // Re-evaluate target row after scroll
                                            val curSel = currentSelection?.normalized() ?: break
                                            val newFirstRow = (session.buffer.totalLines - session.buffer.rows - session.scrollOffset.value).coerceAtLeast(0)
                                            val newTargetAbsRow = if (isNearTop) {
                                                newFirstRow.coerceAtLeast(0)
                                            } else {
                                                (newFirstRow + session.buffer.rows - 1).coerceIn(0, (session.buffer.totalLines - 1).coerceAtLeast(0))
                                            }
                                            val newUpdated = if (isStart) {
                                                TerminalSelection(
                                                    startRow = newTargetAbsRow,
                                                    startCol = curSel.startCol,
                                                    endRow = curSel.endRow,
                                                    endCol = curSel.endCol
                                                ).normalized()
                                            } else {
                                                TerminalSelection(
                                                    startRow = curSel.startRow,
                                                    startCol = curSel.startCol,
                                                    endRow = newTargetAbsRow,
                                                    endCol = curSel.endCol
                                                ).normalized()
                                            }
                                            currentOnSelectionChange(newUpdated)
                                        }
                                    }
                                }
                            } else {
                                autoScrollJob?.cancel()
                                autoScrollJob = null
                            }
                        } else {
                            autoScrollJob?.cancel()
                            autoScrollJob = null
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            // Proportional smooth vertical scroll: accumulate exact pixel delta without resetting pointerInput
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
        // Wallpaper: custom user image if set & exists, otherwise default Wabi-Sabi Bamboo Ink background
        val customBgFile = remember(settings.customBackgroundPath) {
            settings.customBackgroundPath?.let { File(it) }?.takeIf { it.exists() }
        }
        if (customBgFile != null) {
            AsyncImage(
                model = customBgFile,
                contentDescription = "Terminal Background",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = com.example.R.drawable.img_sumi_ink_bg_1794356389341),
                contentDescription = "Default Bamboo Sumi-e Background",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background.copy(alpha = (1f - settings.backgroundBrightness).coerceIn(0.2f, 0.96f)))
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

            val normSel = currentSelection?.normalized()
            val totalRows = session.buffer.rows.coerceAtLeast(1)
            val totalLines = session.buffer.totalLines
            val firstRow = (totalLines - totalRows - scrollOffset).coerceAtLeast(0)

            // 1. Draw selection background mapped from absolute rows to viewport
            if (normSel != null) {
                for (r in 0 until linesSnapshot.size) {
                    val absR = firstRow + r
                    if (absR in normSel.startRow..normSel.endRow) {
                        val yTop = padTopPx + r * cellHeightPx
                        if (yTop >= canvasHeight || yTop + cellHeightPx <= 0f) continue

                        val lineCols = linesSnapshot.getOrNull(r)?.size ?: continue
                        val maxCol = (lineCols - 1).coerceAtLeast(0)
                        val cStart = if (absR == normSel.startRow) normSel.startCol.coerceIn(0, maxCol) else 0
                        val cEnd = if (absR == normSel.endRow) normSel.endCol.coerceIn(0, maxCol) else maxCol
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

            // 2b. Draw text & geometric TUI glyphs via Android Native Canvas
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
                        val xLeft = padLeftPx + idx * cellWidthPx

                        if (first.char == ' ') {
                            // Fast skip empty spaces unless underlined
                            if (first.underline && xLeft < canvasWidth && xLeft + cellWidthPx > 0f) {
                                val fgArgb = first.fgColor?.toArgb() ?: defaultFgArgb
                                textPaint.color = fgArgb
                                textPaint.typeface = if (first.bold) boldTypeface else normalTypeface
                                textPaint.isUnderlineText = true
                                native.drawText(" ", xLeft, yBaseline, textPaint)
                            }
                            idx++
                            continue
                        }

                        if (!TerminalGlyphRenderer.isBasicAscii(first.char)) {
                            // Non-ASCII glyph: render strictly inside its column cell (never coalesce runs!)
                            if (xLeft < canvasWidth && xLeft + cellWidthPx > 0f) {
                                val fgArgb = first.fgColor?.toArgb() ?: defaultFgArgb
                                if (TerminalGlyphRenderer.isGeometricGlyph(first.char)) {
                                    TerminalGlyphRenderer.drawGeometricGlyph(
                                        canvas = native,
                                        ch = first.char,
                                        xLeft = xLeft,
                                        yTop = yTop,
                                        cellWidth = cellWidthPx,
                                        cellHeight = cellHeightPx,
                                        fgArgb = fgArgb,
                                        bold = first.bold
                                    )
                                } else {
                                    textPaint.typeface = if (first.bold) boldTypeface else normalTypeface
                                    textPaint.isUnderlineText = first.underline
                                    TerminalGlyphRenderer.drawConstrainedUnicodeGlyph(
                                        canvas = native,
                                        ch = first.char,
                                        xLeft = xLeft,
                                        yTop = yTop,
                                        yBaseline = yBaseline,
                                        cellWidth = cellWidthPx,
                                        cellHeight = cellHeightPx,
                                        textPaint = textPaint,
                                        fgArgb = fgArgb
                                    )
                                }
                            }
                            idx++
                            continue
                        }

                        // Basic ASCII (0x20..0x7E): safe to coalesce adjacent cells with identical style
                        var runEnd = idx + 1
                        while (
                            runEnd < lineCols &&
                            TerminalGlyphRenderer.isBasicAscii(cells[runEnd].char) &&
                            cells[runEnd].fgColor == first.fgColor &&
                            cells[runEnd].bold == first.bold &&
                            cells[runEnd].underline == first.underline
                        ) {
                            runEnd++
                        }

                        val count = runEnd - idx
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
                val startRelRow = normSel.startRow - firstRow
                val endRelRow = normSel.endRow - firstRow

                val handleRadius = (cellHeightPx * 0.36f).coerceIn(7f, 16f)
                val startX = padLeftPx + normSel.startCol * cellWidthPx
                val startY = padTopPx + (startRelRow + 1) * cellHeightPx
                val endX = padLeftPx + (normSel.endCol + 1) * cellWidthPx
                val endY = padTopPx + (endRelRow + 1) * cellHeightPx

                // Left / Start teardrop handle (only drawn if visible in viewport)
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

                // Right / End teardrop handle (only drawn if visible in viewport)
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

        // Floating Action Pill when selection is active: Copiar | Selecionar tudo | Colar | Limpar (Draggable & clear of handles)
        selection?.normalized()?.let { normSel ->
            val totalRows = session.buffer.rows.coerceAtLeast(1)
            val totalLines = session.buffer.totalLines
            val firstRow = (totalLines - totalRows - scrollOffset).coerceAtLeast(0)
            val startRelRow = (normSel.startRow - firstRow).coerceIn(0, totalRows - 1)

            // Safe default gap: position at least 16dp above handle or below if at screen top
            val handleTopY = padTopPx + (startRelRow + 1) * cellHeightPx
            val clearancePx = with(density) { 20.dp.toPx() }
            val basePillY = if (handleTopY - clearancePx - cellHeightPx * 1.5f > padTopPx) {
                (handleTopY - clearancePx - cellHeightPx * 2f).roundToInt()
            } else {
                (handleTopY + clearancePx + cellHeightPx * 1.2f).roundToInt()
            }
            val safeBasePillY = basePillY.coerceIn(8, (totalRows * cellHeightPx).roundToInt().coerceAtLeast(8))

            val finalX = pillManualOffset.x.roundToInt()
            val finalY = (safeBasePillY + pillManualOffset.y).roundToInt()

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(finalX, finalY) }
                    .pointerInput(normSel) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            pillManualOffset += dragAmount
                        }
                    }
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
