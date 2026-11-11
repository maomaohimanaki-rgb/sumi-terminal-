package dev.sumi.terminal.terminal

import androidx.compose.ui.graphics.Color
import dev.sumi.terminal.ui.theme.Sumi
import dev.sumi.terminal.ui.theme.WabiSabiPalette
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class TermCell(
    val char: Char = ' ',
    val fgColor: Color? = null,
    val bgColor: Color? = null,
    val bold: Boolean = false,
    val underline: Boolean = false
)

class TerminalLine(cols: Int) {
    var cells: Array<TermCell> = Array(cols) { TermCell() }
    var softWrapped: Boolean = false

    fun resize(newCols: Int) {
        if (newCols == cells.size) return
        cells = Array(newCols) { idx -> if (idx < cells.size) cells[idx] else TermCell() }
    }

    fun asPlainString(): String {
        val sb = StringBuilder(cells.size)
        for (c in cells) sb.append(c.char)
        return sb.toString().trimEnd()
    }
}

/**
 * Thread-safe VT100 / Xterm-256color / TrueColor terminal buffer with proper
 * multiline Backspace wrap (0x08 / 0x7F) and Wabi-Sabi semantic command highlighting.
 */
class TerminalBuffer(
    initialRows: Int = 36,
    initialCols: Int = 80,
    private val maxScrollback: Int = 1500
) {
    private val lock = ReentrantLock()

    var rows: Int = initialRows.coerceAtLeast(5)
        private set
    var cols: Int = initialCols.coerceAtLeast(20)
        private set

    private val history = ArrayDeque<TerminalLine>()
    private var screen = Array(rows) { TerminalLine(cols) }

    var curRow: Int = 0
        private set
    var curCol: Int = 0
        private set

    // Current SGR state
    private var currentFg: Color? = null
    private var currentBg: Color? = null
    private var currentBold: Boolean = false
    private var currentUnderline: Boolean = false

    // CSI parser state
    private var parserState = ParserState.NORMAL
    private val csiBuffer = StringBuilder()

    var versionCounter: Long = 0L
        private set

    private enum class ParserState {
        NORMAL, ESCAPE, CSI, OSC, OSC_ESC
    }

    fun appendBytes(bytes: ByteArray, palette: WabiSabiPalette): Unit = lock.withLock {
        val text = bytes.toString(Charsets.UTF_8)
        for (ch in text) {
            processChar(ch, palette)
        }
        versionCounter++
    }

    private fun processChar(ch: Char, palette: WabiSabiPalette) {
        when (parserState) {
            ParserState.NORMAL -> {
                when (ch) {
                    AnsiConstants.ESC -> parserState = ParserState.ESCAPE
                    AnsiConstants.CR -> curCol = 0
                    AnsiConstants.LF -> {
                        curCol = 0
                        lineFeed()
                    }
                    AnsiConstants.BS, AnsiConstants.DEL -> handleBackspace()
                    AnsiConstants.TAB -> {
                        val nextStop = ((curCol / 4) + 1) * 4
                        while (curCol < nextStop && curCol < cols) {
                            putChar(' ')
                        }
                    }
                    else -> {
                        if (ch.code >= 32) {
                            putChar(ch)
                        }
                    }
                }
            }
            ParserState.ESCAPE -> {
                when (ch) {
                    '[' -> {
                        csiBuffer.clear()
                        parserState = ParserState.CSI
                    }
                    ']' -> {
                        csiBuffer.clear()
                        parserState = ParserState.OSC
                    }
                    'c' -> {
                        resetScreen()
                        parserState = ParserState.NORMAL
                    }
                    else -> parserState = ParserState.NORMAL
                }
            }
            ParserState.CSI -> {
                if (ch in '@'..'~') {
                    handleCsi(csiBuffer.toString(), ch, palette)
                    parserState = ParserState.NORMAL
                } else {
                    csiBuffer.append(ch)
                    if (csiBuffer.length > 64) parserState = ParserState.NORMAL
                }
            }
            ParserState.OSC -> {
                when {
                    ch == AnsiConstants.BEL || ch == '\n' || ch == '\r' -> {
                        parserState = ParserState.NORMAL
                    }
                    ch == AnsiConstants.ESC -> {
                        parserState = ParserState.OSC_ESC
                    }
                    else -> {
                        csiBuffer.append(ch)
                        if (csiBuffer.length > 256) parserState = ParserState.NORMAL
                    }
                }
            }
            ParserState.OSC_ESC -> {
                parserState = ParserState.NORMAL
            }
        }
    }

    /**
     * BugFix #3: Proper VT100/ANSI Backspace behavior across soft-wrapped lines.
     * When curCol == 0, wraps back to the end of the previous row if it was soft-wrapped
     * or part of an active command line.
     */
    private fun handleBackspace() {
        if (curCol > 0) {
            curCol--
        } else if (curRow > 0 && screen[curRow - 1].softWrapped) {
            curRow--
            curCol = (cols - 1).coerceAtLeast(0)
        }
    }

    private fun putChar(ch: Char) {
        if (curCol >= cols) {
            screen[curRow].softWrapped = true
            curCol = 0
            lineFeed()
        }
        val line = screen[curRow]
        line.cells[curCol] = TermCell(
            char = ch,
            fgColor = currentFg,
            bgColor = currentBg,
            bold = currentBold,
            underline = currentUnderline
        )
        curCol++
    }

    private fun lineFeed() {
        if (curRow < rows - 1) {
            curRow++
        } else {
            history.addLast(screen[0])
            while (history.size > maxScrollback) {
                history.removeFirst()
            }
            for (r in 0 until rows - 1) {
                screen[r] = screen[r + 1]
            }
            screen[rows - 1] = TerminalLine(cols)
        }
    }

    private fun resetScreen() {
        for (r in 0 until rows) {
            screen[r] = TerminalLine(cols)
        }
        curRow = 0
        curCol = 0
        currentFg = null
        currentBg = null
        currentBold = false
        currentUnderline = false
    }

    private fun handleCsi(paramsRaw: String, finalChar: Char, palette: WabiSabiPalette) {
        val clean = paramsRaw.removePrefix("?")
        val parts = if (clean.isEmpty()) emptyList() else clean.split(';').map { it.toIntOrNull() ?: 0 }
        when (finalChar) {
            'm' -> handleSgr(parts, palette)
            'H', 'f' -> {
                val r = (parts.getOrNull(0) ?: 1).coerceIn(1, rows) - 1
                val c = (parts.getOrNull(1) ?: 1).coerceIn(1, cols) - 1
                curRow = r
                curCol = c
            }
            'A' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                curRow = (curRow - n).coerceAtLeast(0)
            }
            'B' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                curRow = (curRow + n).coerceAtMost(rows - 1)
            }
            'C' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                curCol = (curCol + n).coerceAtMost(cols - 1)
            }
            'D' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                if (curCol - n >= 0) {
                    curCol -= n
                } else if (curRow > 0 && screen[curRow - 1].softWrapped) {
                    val rem = n - curCol
                    curRow--
                    curCol = (cols - rem).coerceIn(0, cols - 1)
                } else {
                    curCol = 0
                }
            }
            'J' -> {
                val mode = parts.firstOrNull() ?: 0
                when (mode) {
                    2, 3 -> {
                        for (r in 0 until rows) screen[r] = TerminalLine(cols)
                        curRow = 0
                        curCol = 0
                    }
                    0 -> {
                        for (c in curCol until cols) screen[curRow].cells[c] = TermCell()
                        for (r in curRow + 1 until rows) screen[r] = TerminalLine(cols)
                    }
                }
            }
            'K' -> {
                val mode = parts.firstOrNull() ?: 0
                val line = screen[curRow]
                when (mode) {
                    0 -> for (c in curCol until cols) line.cells[c] = TermCell()
                    1 -> for (c in 0..curCol.coerceAtMost(cols - 1)) line.cells[c] = TermCell()
                    2 -> {
                        for (c in 0 until cols) line.cells[c] = TermCell()
                        line.softWrapped = false
                    }
                }
            }
        }
    }

    private fun handleSgr(codes: List<Int>, palette: WabiSabiPalette) {
        if (codes.isEmpty()) {
            currentFg = null
            currentBg = null
            currentBold = false
            currentUnderline = false
            return
        }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> {
                    currentFg = null
                    currentBg = null
                    currentBold = false
                    currentUnderline = false
                }
                1 -> currentBold = true
                4 -> currentUnderline = true
                22 -> currentBold = false
                24 -> currentUnderline = false
                in 30..37 -> currentFg = palette.ansiColors[code - 30]
                39 -> currentFg = null
                in 40..47 -> currentBg = palette.ansiColors[code - 40]
                49 -> currentBg = null
                in 90..97 -> currentFg = palette.ansiColors[code - 90 + 8]
                in 100..107 -> currentBg = palette.ansiColors[code - 100 + 8]
                38 -> {
                    if (i + 4 < codes.size && codes[i + 1] == 2) {
                        val r = codes[i + 2].coerceIn(0, 255)
                        val g = codes[i + 3].coerceIn(0, 255)
                        val b = codes[i + 4].coerceIn(0, 255)
                        currentFg = Color(r, g, b)
                        i += 4
                    }
                }
                48 -> {
                    if (i + 4 < codes.size && codes[i + 1] == 2) {
                        val r = codes[i + 2].coerceIn(0, 255)
                        val g = codes[i + 3].coerceIn(0, 255)
                        val b = codes[i + 4].coerceIn(0, 255)
                        currentBg = Color(r, g, b)
                        i += 4
                    }
                }
            }
            i++
        }
    }

    fun resize(newRows: Int, newCols: Int): Unit = lock.withLock {
        val targetRows = newRows.coerceAtLeast(5)
        val targetCols = newCols.coerceAtLeast(20)
        if (targetRows == rows && targetCols == cols) return

        history.forEach { it.resize(targetCols) }
        val newScreen = Array(targetRows) { r ->
            if (r < screen.size) {
                screen[r].apply { resize(targetCols) }
            } else {
                TerminalLine(targetCols)
            }
        }
        screen = newScreen
        rows = targetRows
        cols = targetCols
        curRow = curRow.coerceIn(0, rows - 1)
        curCol = curCol.coerceIn(0, cols - 1)
        versionCounter++
    }

    /**
     * Returns a snapshot of visible lines together with semantic Wabi-Sabi coloring overrides:
     * - Specification D: Only the first executable token of a command line is highlighted
     *   in `palette.commandHighlight`; flags/arguments stay `Shironeri`.
     * - Global error keywords (`error:`, `failed:`, `fatal:`) are highlighted in `Kurenai`.
     */
    fun snapshotScreen(
        palette: WabiSabiPalette,
        autoCommandColors: Boolean
    ): Pair<List<Array<TermCell>>, Pair<Int, Int>> = lock.withLock {
        val out = ArrayList<Array<TermCell>>(rows)
        for (r in 0 until rows) {
            val rawCells = screen[r].cells.copyOf()
            if (autoCommandColors) {
                applySemanticColoring(rawCells, palette)
            }
            out.add(rawCells)
        }
        out to (curRow to curCol)
    }

    private fun applySemanticColoring(cells: Array<TermCell>, palette: WabiSabiPalette) {
        val lineStr = String(CharArray(cells.size) { cells[it].char })
        val lower = lineStr.lowercase()

        // 1. Highlight error prefixes in Kurenai (0xFFD9383A)
        for (errKw in AnsiConstants.ERROR_KEYWORDS) {
            val idx = lower.indexOf(errKw)
            if (idx >= 0) {
                for (c in idx until (idx + errKw.length).coerceAtMost(cells.size)) {
                    cells[c] = cells[c].copy(fgColor = Sumi.Kurenai, bold = true)
                }
            }
        }

        // 2. Highlight only the first executable token after prompt "$ " or "# "
        val promptEnd = lineStr.indexOf("$ ").takeIf { it >= 0 }?.plus(2)
            ?: lineStr.indexOf("# ").takeIf { it >= 0 }?.plus(2)
        if (promptEnd != null && promptEnd < lineStr.length) {
            var tokenStart = promptEnd
            while (tokenStart < lineStr.length && lineStr[tokenStart] == ' ') tokenStart++
            var tokenEnd = tokenStart
            while (tokenEnd < lineStr.length && !lineStr[tokenEnd].isWhitespace()) tokenEnd++
            if (tokenEnd > tokenStart) {
                val firstToken = lineStr.substring(tokenStart, tokenEnd)
                val color = if (firstToken in AnsiConstants.KNOWN_EXECUTABLES) {
                    palette.commandHighlight
                } else {
                    palette.primaryAccent
                }
                for (c in tokenStart until tokenEnd) {
                    cells[c] = cells[c].copy(fgColor = color, bold = true)
                }
                // Parameters (--flags, paths) remain in Shironeri unless explicitly colored
                for (c in tokenEnd until lineStr.length) {
                    if (cells[c].fgColor == null) {
                        cells[c] = cells[c].copy(fgColor = Sumi.Shironeri)
                    }
                }
            }
        }
    }

    fun getLineString(row: Int): String = lock.withLock {
        if (row !in 0 until rows) return ""
        String(CharArray(cols) { screen[row].cells[it].char })
    }

    fun selectAll(): TerminalSelection = lock.withLock {
        var firstRow = 0
        var lastRow = rows - 1
        var foundNonEmpty = false

        for (r in 0 until rows) {
            if (screen[r].cells.any { it.char != ' ' }) {
                firstRow = r
                foundNonEmpty = true
                break
            }
        }

        if (!foundNonEmpty) {
            return@withLock TerminalSelection(
                startRow = 0,
                startCol = 0,
                endRow = (rows - 1).coerceAtLeast(0),
                endCol = (cols - 1).coerceAtLeast(0)
            )
        }

        for (r in (rows - 1) downTo firstRow) {
            if (screen[r].cells.any { it.char != ' ' }) {
                lastRow = r
                break
            }
        }

        var lastCol = cols - 1
        for (c in (cols - 1) downTo 0) {
            if (screen[lastRow].cells[c].char != ' ') {
                lastCol = c
                break
            }
        }

        TerminalSelection(
            startRow = firstRow,
            startCol = 0,
            endRow = lastRow,
            endCol = lastCol.coerceAtLeast(0)
        )
    }

    fun extract(selection: TerminalSelection): String = extractSelectedText(selection)

    fun extractSelectedText(selection: TerminalSelection): String = lock.withLock {
        val norm = selection.normalized()
        val sb = StringBuilder()
        for (r in norm.startRow..norm.endRow.coerceAtMost(rows - 1)) {
            if (r < 0) continue
            val line = screen[r].cells
            val cStart = if (r == norm.startRow) norm.startCol.coerceIn(0, cols - 1) else 0
            val cEnd = if (r == norm.endRow) norm.endCol.coerceIn(0, cols - 1) else cols - 1
            if (cEnd >= cStart) {
                val chunk = StringBuilder()
                for (c in cStart..cEnd) {
                    chunk.append(line[c].char)
                }
                sb.append(chunk.toString().trimEnd())
            }
            if (r < norm.endRow) sb.append('\n')
        }
        sb.toString().trimEnd()
    }
}
