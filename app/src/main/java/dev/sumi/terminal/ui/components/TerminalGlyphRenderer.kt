package dev.sumi.terminal.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * High-precision geometric & per-cell glyph renderer for modern TUIs (Claude Code, Neovim, htop, btop).
 *
 * Solves:
 * 1. Cell aspect ratio & lineHeight: ~1.13x of actual font textSize (e.g. ~24.75px x ~46.5px, ratio ~0.53).
 * 2. Strict column positioning & horizontal clipping/scaling for non-ASCII glyphs.
 * 3. Seamless geometric rendering for U+2500..U+257F (Box Drawing) and U+2580..U+259F (Block Elements).
 * 4. 24-bit ANSI TrueColor preservation on Unicode Dingbats/Geometric symbols (avoiding NotoColorEmoji fallback).
 */
object TerminalGlyphRenderer {

    private val geomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reusablePath = Path()
    private val reusableRect = RectF()

    fun isBasicAscii(ch: Char): Boolean {
        val code = ch.code
        return code in 0x20..0x7E
    }

    /**
     * Returns true if the character is rendered geometrically via Canvas primitives
     * rather than font glyphs.
     */
    fun isGeometricGlyph(ch: Char): Boolean {
        val code = ch.code
        return code in 0x2500..0x259F ||
            code == 0x2022 || // • Bullet
            code == 0x25A0 || // ■ Black Square
            code == 0x25A1 || // □ White Square
            code == 0x25AA || // ▪ Black Small Square
            code == 0x25AB || // ▫ White Small Square
            code == 0x25AE || // ▮ Black Vertical Rectangle
            code == 0x25AF || // ▯ White Vertical Rectangle
            code == 0x25B6 || // ▶ Black Right-Pointing Triangle
            code == 0x25C0 || // ◀ Black Left-Pointing Triangle
            code == 0x25C6 || // ◆ Black Diamond
            code == 0x25CB || // ○ White Circle
            code == 0x25CF || // ● Black Circle
            code == 0x276F || // ❯ Heavy Right-Pointing Angle Quotation Mark Ornament (Claude/Starship prompt)
            code in 0x2731..0x273D // ✱ ✲ ✳ ✴ ✵ ✶ ✷ ✸ ✹ ✺ ✻ ✼ ✽ (Claude spinner / TUI stars)
    }

    fun drawGeometricGlyph(
        canvas: Canvas,
        ch: Char,
        xLeft: Float,
        yTop: Float,
        cellWidth: Float,
        cellHeight: Float,
        fgArgb: Int,
        bold: Boolean
    ) {
        val code = ch.code
        when {
            code in 0x2500..0x257F -> drawBoxDrawing(
                canvas = canvas,
                code = code,
                xLeft = xLeft,
                yTop = yTop,
                w = cellWidth,
                h = cellHeight,
                fgArgb = fgArgb,
                bold = bold
            )
            code in 0x2580..0x259F -> drawBlockElement(
                canvas = canvas,
                code = code,
                xLeft = xLeft,
                yTop = yTop,
                w = cellWidth,
                h = cellHeight,
                fgArgb = fgArgb
            )
            else -> drawTuiSymbol(
                canvas = canvas,
                code = code,
                xLeft = xLeft,
                yTop = yTop,
                w = cellWidth,
                h = cellHeight,
                fgArgb = fgArgb,
                bold = bold
            )
        }
    }

    /**
     * Draws a non-ASCII text character strictly confined to a single cell [xLeft, xLeft + cellWidth],
     * forcing monochrome text presentation (\uFE0E) and 24-bit color tint so Android never renders
     * a wide multicolor bitmap emoji that ignores ANSI 24-bit colors.
     */
    fun drawConstrainedUnicodeGlyph(
        canvas: Canvas,
        ch: Char,
        xLeft: Float,
        yTop: Float,
        yBaseline: Float,
        cellWidth: Float,
        cellHeight: Float,
        textPaint: Paint,
        fgArgb: Int
    ) {
        // Use Variation Selector-15 (\uFE0E) for dingbats/symbols to prevent NotoColorEmoji fallback
        val useTextSelector = isLikelyEmojiFallbackSymbol(ch.code)
        val textStr = if (useTextSelector) "$ch\uFE0E" else ch.toString()

        val prevFilter = textPaint.colorFilter
        textPaint.color = fgArgb
        // Apply SRC_IN color filter so even if a system fallback font has embedded color tables,
        // it strictly renders in the terminal's 24-bit ANSI foreground color.
        textPaint.colorFilter = PorterDuffColorFilter(fgArgb, PorterDuff.Mode.SRC_IN)

        val rawAdvance = textPaint.measureText(ch.toString()).coerceAtLeast(1f)
        canvas.save()
        // Clip strictly to the cell width so wide glyphs never bleed into the next column
        canvas.clipRect(xLeft, yTop, xLeft + cellWidth, yTop + cellHeight)
        if (rawAdvance > cellWidth * 1.02f) {
            val scaleX = (cellWidth / rawAdvance).coerceIn(0.45f, 1f)
            canvas.scale(scaleX, 1f, xLeft, yBaseline)
        }
        canvas.drawText(textStr, xLeft, yBaseline, textPaint)
        canvas.restore()

        textPaint.colorFilter = prevFilter
    }

    private fun isLikelyEmojiFallbackSymbol(code: Int): Boolean {
        return code in 0x2000..0x2BFF || code in 0x3000..0x32FF
    }

    /**
     * Renders Block Elements (U+2580..U+259F) with exact cell geometry and subpixel-sealed seams.
     */
    private fun drawBlockElement(
        canvas: Canvas,
        code: Int,
        xLeft: Float,
        yTop: Float,
        w: Float,
        h: Float,
        fgArgb: Int
    ) {
        geomPaint.reset()
        geomPaint.isAntiAlias = false // Crisp pixel-aligned edges for seamless blocks
        geomPaint.style = Paint.Style.FILL
        geomPaint.color = fgArgb

        // Slight overlap (+0.35f) prevents hairline gaps between adjacent block cells on fractional DPIs
        val eps = 0.35f
        val xRight = xLeft + w + eps
        val yBottom = yTop + h + eps
        val xMid = xLeft + w * 0.5f
        val yMid = yTop + h * 0.5f

        when (code) {
            0x2580 -> { // ▀ Upper half block
                canvas.drawRect(xLeft, yTop, xRight, yMid + eps, geomPaint)
            }
            in 0x2581..0x2587 -> { // ▁ ▂ ▃ ▄ ▅ ▆ ▇ Lower 1/8 to 7/8 blocks
                val eighths = code - 0x2580
                val topY = yTop + h * (1f - eighths / 8f)
                canvas.drawRect(xLeft, topY, xRight, yBottom, geomPaint)
            }
            0x2588 -> { // █ Full block
                canvas.drawRect(xLeft, yTop, xRight, yBottom, geomPaint)
            }
            in 0x2589..0x258F -> { // ▉ ▊ ▋ ▌ ▍ ▎ ▏ Left 7/8 down to 1/8 blocks
                val eighths = 8 - (code - 0x2588)
                val rightX = xLeft + w * (eighths / 8f)
                canvas.drawRect(xLeft, yTop, rightX + eps, yBottom, geomPaint)
            }
            0x2590 -> { // ▐ Right half block
                canvas.drawRect(xMid, yTop, xRight, yBottom, geomPaint)
            }
            0x2591 -> { // ░ Light shade (25%)
                geomPaint.alpha = ((fgArgb ushr 24) * 0.25f).toInt().coerceIn(0, 255)
                canvas.drawRect(xLeft, yTop, xRight, yBottom, geomPaint)
            }
            0x2592 -> { // ▒ Medium shade (50%)
                geomPaint.alpha = ((fgArgb ushr 24) * 0.50f).toInt().coerceIn(0, 255)
                canvas.drawRect(xLeft, yTop, xRight, yBottom, geomPaint)
            }
            0x2593 -> { // ▓ Dark shade (75%)
                geomPaint.alpha = ((fgArgb ushr 24) * 0.75f).toInt().coerceIn(0, 255)
                canvas.drawRect(xLeft, yTop, xRight, yBottom, geomPaint)
            }
            0x2594 -> { // ▔ Upper 1/8 block
                canvas.drawRect(xLeft, yTop, xRight, yTop + h / 8f + eps, geomPaint)
            }
            0x2595 -> { // ▕ Right 1/8 block
                canvas.drawRect(xLeft + w * (7f / 8f), yTop, xRight, yBottom, geomPaint)
            }
            in 0x2596..0x259F -> {
                // Quadrant bitmask: bit0 = UL, bit1 = UR, bit2 = BL, bit3 = BR
                val mask = when (code) {
                    0x2596 -> 0b0100 // ▖ BL
                    0x2597 -> 0b1000 // ▗ BR
                    0x2598 -> 0b0001 // ▘ UL
                    0x2599 -> 0b1101 // ▙ UL + BL + BR
                    0x259A -> 0b1001 // ▚ UL + BR
                    0x259B -> 0b0111 // ▛ UL + UR + BL
                    0x259C -> 0b1011 // ▜ UL + UR + BR
                    0x259D -> 0b0010 // ▝ UR
                    0x259E -> 0b0110 // ▞ UR + BL
                    0x259F -> 0b1110 // ▟ UR + BL + BR
                    else -> 0
                }
                if ((mask and 0b0001) != 0) { // UL
                    canvas.drawRect(xLeft, yTop, xMid + eps, yMid + eps, geomPaint)
                }
                if ((mask and 0b0010) != 0) { // UR
                    canvas.drawRect(xMid, yTop, xRight, yMid + eps, geomPaint)
                }
                if ((mask and 0b0100) != 0) { // BL
                    canvas.drawRect(xLeft, yMid, xMid + eps, yBottom, geomPaint)
                }
                if ((mask and 0b1000) != 0) { // BR
                    canvas.drawRect(xMid, yMid, xRight, yBottom, geomPaint)
                }
            }
        }
    }

    /**
     * Renders Box Drawing characters (U+2500..U+257F) from edge to edge of the cell
     * so horizontal and vertical lines never show gaps between cells.
     */
    private fun drawBoxDrawing(
        canvas: Canvas,
        code: Int,
        xLeft: Float,
        yTop: Float,
        w: Float,
        h: Float,
        fgArgb: Int,
        bold: Boolean
    ) {
        val cx = xLeft + w * 0.5f
        val cy = yTop + h * 0.5f
        val xRight = xLeft + w + 0.4f
        val yBottom = yTop + h + 0.4f

        val lightStroke = (w * 0.085f).coerceIn(1.4f, 3.2f) * if (bold) 1.25f else 1f
        val heavyStroke = (lightStroke * 1.85f).coerceAtLeast(2.8f)

        geomPaint.reset()
        geomPaint.isAntiAlias = true
        geomPaint.color = fgArgb
        geomPaint.strokeCap = Paint.Cap.BUTT
        geomPaint.strokeJoin = Paint.Join.MITER

        // Handle rounded corners (╭ ╮ ╯ ╰ U+256D..U+2570)
        if (code in 0x256D..0x2570) {
            geomPaint.style = Paint.Style.STROKE
            geomPaint.strokeWidth = lightStroke
            reusablePath.reset()
            val r = min(w, h) * 0.42f
            when (code) {
                0x256D -> { // ╭ down and right
                    reusablePath.moveTo(cx, yBottom)
                    reusablePath.lineTo(cx, cy + r)
                    reusableRect.set(cx, cy, cx + 2f * r, cy + 2f * r)
                    reusablePath.arcTo(reusableRect, 180f, 90f, false)
                    reusablePath.lineTo(xRight, cy)
                }
                0x256E -> { // ╮ down and left
                    reusablePath.moveTo(cx, yBottom)
                    reusablePath.lineTo(cx, cy + r)
                    reusableRect.set(cx - 2f * r, cy, cx, cy + 2f * r)
                    reusablePath.arcTo(reusableRect, 0f, -90f, false)
                    reusablePath.lineTo(xLeft, cy)
                }
                0x256F -> { // ╯ up and left
                    reusablePath.moveTo(cx, yTop)
                    reusablePath.lineTo(cx, cy - r)
                    reusableRect.set(cx - 2f * r, cy - 2f * r, cx, cy)
                    reusablePath.arcTo(reusableRect, 0f, 90f, false)
                    reusablePath.lineTo(xLeft, cy)
                }
                0x2570 -> { // ╰ up and right
                    reusablePath.moveTo(cx, yTop)
                    reusablePath.lineTo(cx, cy - r)
                    reusableRect.set(cx, cy - 2f * r, cx + 2f * r, cy)
                    reusablePath.arcTo(reusableRect, 180f, -90f, false)
                    reusablePath.lineTo(xRight, cy)
                }
            }
            canvas.drawPath(reusablePath, geomPaint)
            return
        }

        // Handle diagonals (╱ ╲ ╳ U+2571..U+2573)
        if (code in 0x2571..0x2573) {
            geomPaint.style = Paint.Style.STROKE
            geomPaint.strokeWidth = lightStroke
            if (code == 0x2571 || code == 0x2573) {
                canvas.drawLine(xRight, yTop, xLeft, yBottom, geomPaint)
            }
            if (code == 0x2572 || code == 0x2573) {
                canvas.drawLine(xLeft, yTop, xRight, yBottom, geomPaint)
            }
            return
        }

        // Decode 4-arm weights: (left, right, up, down) where 0=none, 1=light, 2=heavy, 3=double
        val arms = boxDrawingArms(code)
        val leftW = (arms ushr 12) and 0xF
        val rightW = (arms ushr 8) and 0xF
        val upW = (arms ushr 4) and 0xF
        val downW = arms and 0xF

        geomPaint.isAntiAlias = false
        geomPaint.style = Paint.Style.FILL

        val doubleOffset = (lightStroke * 1.15f).coerceAtLeast(2.0f)

        fun drawArmH(x0: Float, x1: Float, weight: Int) {
            when (weight) {
                1 -> canvas.drawRect(x0, cy - lightStroke * 0.5f, x1, cy + lightStroke * 0.5f, geomPaint)
                2 -> canvas.drawRect(x0, cy - heavyStroke * 0.5f, x1, cy + heavyStroke * 0.5f, geomPaint)
                3 -> {
                    canvas.drawRect(x0, cy - doubleOffset - lightStroke * 0.5f, x1, cy - doubleOffset + lightStroke * 0.5f, geomPaint)
                    canvas.drawRect(x0, cy + doubleOffset - lightStroke * 0.5f, x1, cy + doubleOffset + lightStroke * 0.5f, geomPaint)
                }
            }
        }

        fun drawArmV(y0: Float, y1: Float, weight: Int) {
            when (weight) {
                1 -> canvas.drawRect(cx - lightStroke * 0.5f, y0, cx + lightStroke * 0.5f, y1, geomPaint)
                2 -> canvas.drawRect(cx - heavyStroke * 0.5f, y0, cx + heavyStroke * 0.5f, y1, geomPaint)
                3 -> {
                    canvas.drawRect(cx - doubleOffset - lightStroke * 0.5f, y0, cx - doubleOffset + lightStroke * 0.5f, y1, geomPaint)
                    canvas.drawRect(cx + doubleOffset - lightStroke * 0.5f, y0, cx + doubleOffset + lightStroke * 0.5f, y1, geomPaint)
                }
            }
        }

        // Fast path for full horizontal line
        if (leftW == rightW && leftW > 0 && upW == 0 && downW == 0) {
            drawArmH(xLeft, xRight, leftW)
            return
        }
        // Fast path for full vertical line
        if (upW == downW && upW > 0 && leftW == 0 && rightW == 0) {
            drawArmV(yTop, yBottom, upW)
            return
        }

        val centerExtend = (if (leftW == 2 || rightW == 2 || upW == 2 || downW == 2) heavyStroke else lightStroke) * 0.5f
        if (leftW > 0) drawArmH(xLeft, cx + centerExtend, leftW)
        if (rightW > 0) drawArmH(cx - centerExtend, xRight, rightW)
        if (upW > 0) drawArmV(yTop, cy + centerExtend, upW)
        if (downW > 0) drawArmV(cy - centerExtend, yBottom, downW)
    }

    private fun packArms(l: Int, r: Int, u: Int, d: Int): Int {
        return (l shl 12) or (r shl 8) or (u shl 4) or d
    }

    private fun boxDrawingArms(code: Int): Int = when (code) {
        0x2500, 0x2504, 0x2508, 0x254C -> packArms(1, 1, 0, 0) // ─ ┄ ┈ ╌
        0x2501, 0x2505, 0x2509, 0x254D -> packArms(2, 2, 0, 0) // ━ ┅ ┉ ╍
        0x2502, 0x2506, 0x250A, 0x254E -> packArms(0, 0, 1, 1) // │ ┆ ┊ ╎
        0x2503, 0x2507, 0x250B, 0x254F -> packArms(0, 0, 2, 2) // ┃ ┇ ┋ ╏

        0x250C -> packArms(0, 1, 0, 1) // ┌
        0x250D -> packArms(0, 2, 0, 1) // ┍
        0x250E -> packArms(0, 1, 0, 2) // ┎
        0x250F -> packArms(0, 2, 0, 2) // ┏

        0x2510 -> packArms(1, 0, 0, 1) // ┐
        0x2511 -> packArms(2, 0, 0, 1) // ┑
        0x2512 -> packArms(1, 0, 0, 2) // ┒
        0x2513 -> packArms(2, 0, 0, 2) // ┓

        0x2514 -> packArms(0, 1, 1, 0) // └
        0x2515 -> packArms(0, 2, 1, 0) // ┕
        0x2516 -> packArms(0, 1, 2, 0) // ┖
        0x2517 -> packArms(0, 2, 2, 0) // ┗

        0x2518 -> packArms(1, 0, 1, 0) // ┘
        0x2519 -> packArms(2, 0, 1, 0) // ┙
        0x251A -> packArms(1, 0, 2, 0) // ┚
        0x251B -> packArms(2, 0, 2, 0) // ┛

        0x251C -> packArms(0, 1, 1, 1) // ├
        0x251D -> packArms(0, 2, 1, 1) // ┝
        0x251E -> packArms(0, 1, 2, 1) // ┞
        0x251F -> packArms(0, 1, 1, 2) // ┟
        0x2520 -> packArms(0, 1, 2, 2) // ┠
        0x2521 -> packArms(0, 2, 2, 1) // ┡
        0x2522 -> packArms(0, 2, 1, 2) // ┢
        0x2523 -> packArms(0, 2, 2, 2) // ┣

        0x2524 -> packArms(1, 0, 1, 1) // ┤
        0x2525 -> packArms(2, 0, 1, 1) // ┥
        0x2526 -> packArms(1, 0, 2, 1) // ┦
        0x2527 -> packArms(1, 0, 1, 2) // ┧
        0x2528 -> packArms(1, 0, 2, 2) // ┨
        0x2529 -> packArms(2, 0, 2, 1) // ┩
        0x252A -> packArms(2, 0, 1, 2) // ┪
        0x252B -> packArms(2, 0, 2, 2) // ┫

        0x252C -> packArms(1, 1, 0, 1) // ┬
        0x252D -> packArms(2, 1, 0, 1) // ┭
        0x252E -> packArms(1, 2, 0, 1) // ┮
        0x252F -> packArms(2, 2, 0, 1) // ┯
        0x2530 -> packArms(1, 1, 0, 2) // ┰
        0x2531 -> packArms(2, 1, 0, 2) // ┱
        0x2532 -> packArms(1, 2, 0, 2) // ┲
        0x2533 -> packArms(2, 2, 0, 2) // ┳

        0x2534 -> packArms(1, 1, 1, 0) // ┴
        0x2535 -> packArms(2, 1, 1, 0) // ┵
        0x2536 -> packArms(1, 2, 1, 0) // ┶
        0x2537 -> packArms(2, 2, 1, 0) // ┷
        0x2538 -> packArms(1, 1, 2, 0) // ┸
        0x2539 -> packArms(2, 1, 2, 0) // ┹
        0x253A -> packArms(1, 2, 2, 0) // ┺
        0x253B -> packArms(2, 2, 2, 0) // ┻

        0x253C -> packArms(1, 1, 1, 1) // ┼
        0x253D -> packArms(2, 1, 1, 1) // ┽
        0x253E -> packArms(1, 2, 1, 1) // ┾
        0x253F -> packArms(2, 2, 1, 1) // ┿
        0x2540 -> packArms(1, 1, 2, 1) // ╀
        0x2541 -> packArms(1, 1, 1, 2) // ╁
        0x2542 -> packArms(1, 1, 2, 2) // ╂
        0x2543 -> packArms(2, 1, 2, 1) // ╃
        0x2544 -> packArms(1, 2, 2, 1) // ╄
        0x2545 -> packArms(2, 1, 1, 2) // ╅
        0x2546 -> packArms(1, 2, 1, 2) // ╆
        0x2547 -> packArms(2, 2, 2, 1) // ╇
        0x2548 -> packArms(2, 2, 1, 2) // ╈
        0x2549 -> packArms(2, 1, 2, 2) // ╉
        0x254A -> packArms(1, 2, 2, 2) // ╊
        0x254B -> packArms(2, 2, 2, 2) // ╋

        // Double lines (U+2550..U+256C)
        0x2550 -> packArms(3, 3, 0, 0) // ═
        0x2551 -> packArms(0, 0, 3, 3) // ║
        0x2552 -> packArms(0, 3, 0, 1) // ╒
        0x2553 -> packArms(0, 1, 0, 3) // ╓
        0x2554 -> packArms(0, 3, 0, 3) // ╔
        0x2555 -> packArms(3, 0, 0, 1) // ╕
        0x2556 -> packArms(1, 0, 0, 3) // ╖
        0x2557 -> packArms(3, 0, 0, 3) // ╗
        0x2558 -> packArms(0, 3, 1, 0) // ╘
        0x2559 -> packArms(0, 1, 3, 0) // ╙
        0x255A -> packArms(0, 3, 3, 0) // ╚
        0x255B -> packArms(3, 0, 1, 0) // ╛
        0x255C -> packArms(1, 0, 3, 0) // ╜
        0x255D -> packArms(3, 0, 3, 0) // ╝
        0x255E -> packArms(0, 3, 1, 1) // ╞
        0x255F -> packArms(0, 1, 3, 3) // ╟
        0x2560 -> packArms(0, 3, 3, 3) // ╠
        0x2561 -> packArms(3, 0, 1, 1) // ╡
        0x2562 -> packArms(1, 0, 3, 3) // ╢
        0x2563 -> packArms(3, 0, 3, 3) // ╣
        0x2564 -> packArms(3, 3, 0, 1) // ╤
        0x2565 -> packArms(1, 1, 0, 3) // ╥
        0x2566 -> packArms(3, 3, 0, 3) // ╦
        0x2567 -> packArms(3, 3, 1, 0) // ╧
        0x2568 -> packArms(1, 1, 3, 0) // ╨
        0x2569 -> packArms(3, 3, 3, 0) // ╩
        0x256A -> packArms(3, 3, 1, 1) // ╪
        0x256B -> packArms(1, 1, 3, 3) // ╫
        0x256C -> packArms(3, 3, 3, 3) // ╬

        // Half lines (U+2574..U+257F)
        0x2574 -> packArms(1, 0, 0, 0) // ╴
        0x2575 -> packArms(0, 0, 1, 0) // ╵
        0x2576 -> packArms(0, 1, 0, 0) // ╶
        0x2577 -> packArms(0, 0, 0, 1) // ╷
        0x2578 -> packArms(2, 0, 0, 0) // ╸
        0x2579 -> packArms(0, 0, 2, 0) // ╹
        0x257A -> packArms(0, 2, 0, 0) // ╺
        0x257B -> packArms(0, 0, 0, 2) // ╻
        0x257C -> packArms(1, 2, 0, 0) // ╼
        0x257D -> packArms(0, 0, 1, 2) // ╽
        0x257E -> packArms(2, 1, 0, 0) // ╾
        0x257F -> packArms(0, 0, 2, 1) // ╿
        else -> packArms(1, 1, 0, 0)
    }

    /**
     * Renders common TUI geometric/dingbat symbols (•, ●, ○, ▮, ▪, ■, ❯, ✻, etc.)
     * using pure 24-bit vector geometry strictly inside 1 cell width.
     */
    private fun drawTuiSymbol(
        canvas: Canvas,
        code: Int,
        xLeft: Float,
        yTop: Float,
        w: Float,
        h: Float,
        fgArgb: Int,
        bold: Boolean
    ) {
        val cx = xLeft + w * 0.5f
        val cy = yTop + h * 0.5f
        val minDim = min(w, h)

        geomPaint.reset()
        geomPaint.isAntiAlias = true
        geomPaint.color = fgArgb

        when (code) {
            0x2022 -> { // • Bullet
                geomPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, (minDim * 0.19f).coerceAtLeast(2f), geomPaint)
            }
            0x25CF -> { // ● Black Circle
                geomPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, (minDim * 0.34f).coerceAtLeast(3f), geomPaint)
            }
            0x25CB -> { // ○ White Circle
                geomPaint.style = Paint.Style.STROKE
                geomPaint.strokeWidth = (minDim * 0.09f).coerceAtLeast(1.5f)
                canvas.drawCircle(cx, cy, (minDim * 0.32f).coerceAtLeast(3f), geomPaint)
            }
            0x25A0 -> { // ■ Black Square
                geomPaint.style = Paint.Style.FILL
                val r = minDim * 0.34f
                canvas.drawRect(cx - r, cy - r, cx + r, cy + r, geomPaint)
            }
            0x25A1 -> { // □ White Square
                geomPaint.style = Paint.Style.STROKE
                geomPaint.strokeWidth = (minDim * 0.09f).coerceAtLeast(1.5f)
                val r = minDim * 0.32f
                canvas.drawRect(cx - r, cy - r, cx + r, cy + r, geomPaint)
            }
            0x25AA -> { // ▪ Black Small Square
                geomPaint.style = Paint.Style.FILL
                val r = minDim * 0.22f
                canvas.drawRect(cx - r, cy - r, cx + r, cy + r, geomPaint)
            }
            0x25AB -> { // ▫ White Small Square
                geomPaint.style = Paint.Style.STROKE
                geomPaint.strokeWidth = (minDim * 0.08f).coerceAtLeast(1.2f)
                val r = minDim * 0.22f
                canvas.drawRect(cx - r, cy - r, cx + r, cy + r, geomPaint)
            }
            0x25AE -> { // ▮ Black Vertical Rectangle
                geomPaint.style = Paint.Style.FILL
                val rw = w * 0.32f
                val rh = h * 0.34f
                canvas.drawRect(cx - rw, cy - rh, cx + rw, cy + rh, geomPaint)
            }
            0x25AF -> { // ▯ White Vertical Rectangle
                geomPaint.style = Paint.Style.STROKE
                geomPaint.strokeWidth = (minDim * 0.08f).coerceAtLeast(1.4f)
                val rw = w * 0.30f
                val rh = h * 0.32f
                canvas.drawRect(cx - rw, cy - rh, cx + rw, cy + rh, geomPaint)
            }
            0x25B6 -> { // ▶ Black Right-Pointing Triangle
                geomPaint.style = Paint.Style.FILL
                val rx = w * 0.32f
                val ry = minDim * 0.32f
                reusablePath.reset()
                reusablePath.moveTo(cx - rx * 0.8f, cy - ry)
                reusablePath.lineTo(cx + rx, cy)
                reusablePath.lineTo(cx - rx * 0.8f, cy + ry)
                reusablePath.close()
                canvas.drawPath(reusablePath, geomPaint)
            }
            0x25C0 -> { // ◀ Black Left-Pointing Triangle
                geomPaint.style = Paint.Style.FILL
                val rx = w * 0.32f
                val ry = minDim * 0.32f
                reusablePath.reset()
                reusablePath.moveTo(cx + rx * 0.8f, cy - ry)
                reusablePath.lineTo(cx - rx, cy)
                reusablePath.lineTo(cx + rx * 0.8f, cy + ry)
                reusablePath.close()
                canvas.drawPath(reusablePath, geomPaint)
            }
            0x25C6 -> { // ◆ Black Diamond
                geomPaint.style = Paint.Style.FILL
                val r = minDim * 0.36f
                reusablePath.reset()
                reusablePath.moveTo(cx, cy - r)
                reusablePath.lineTo(cx + r, cy)
                reusablePath.lineTo(cx, cy + r)
                reusablePath.lineTo(cx - r, cy)
                reusablePath.close()
                canvas.drawPath(reusablePath, geomPaint)
            }
            0x276F -> { // ❯ Heavy Right-Pointing Angle Quotation Mark Ornament
                geomPaint.style = Paint.Style.STROKE
                geomPaint.strokeCap = Paint.Cap.ROUND
                geomPaint.strokeJoin = Paint.Join.ROUND
                geomPaint.strokeWidth = (minDim * (if (bold) 0.16f else 0.13f)).coerceAtLeast(2.0f)
                val dx = w * 0.22f
                val dy = minDim * 0.28f
                reusablePath.reset()
                reusablePath.moveTo(cx - dx, cy - dy)
                reusablePath.lineTo(cx + dx, cy)
                reusablePath.lineTo(cx - dx, cy + dy)
                canvas.drawPath(reusablePath, geomPaint)
            }
            in 0x2731..0x273D -> { // ✻ Teardrop-spoked / 6-spoked / 8-spoked asterisks
                geomPaint.style = Paint.Style.STROKE
                geomPaint.strokeCap = Paint.Cap.ROUND
                geomPaint.strokeWidth = (minDim * 0.11f).coerceAtLeast(1.6f)
                val r = minDim * 0.34f
                val spokes = if (code in 0x2734..0x273A) 8 else 6
                for (i in 0 until spokes) {
                    val angle = (i * Math.PI) / (spokes / 2.0)
                    val dx = (cos(angle) * r).toFloat()
                    val dy = (sin(angle) * r).toFloat()
                    canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, geomPaint)
                }
            }
        }
    }
}
