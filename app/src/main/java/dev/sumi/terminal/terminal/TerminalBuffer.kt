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
    var cells: Array<TermCell> = Array(cols.coerceAtLeast(1)) { TermCell() }
    var softWrapped: Boolean = false

    fun resize(newCols: Int) {
        val safeCols = newCols.coerceAtLeast(1)
        if (safeCols == cells.size) return
        val old = cells
        cells = Array(safeCols) { idx -> old.getOrNull(idx) ?: TermCell() }
    }

    fun asPlainString(): String {
        val snapshot = cells
        val sb = StringBuilder(snapshot.size)
        for (c in snapshot) sb.append(c.char)
        return sb.toString().trimEnd()
    }
}

/**
 * Thread-safe VT100 / Xterm-256color / TrueColor terminal buffer with proper
 * multiline Backspace wrap (0x08 / 0x7F), null-safe `.getOrNull()` line fallbacks,
 * and Wabi-Sabi semantic command highlighting.
 */
class TerminalBuffer(
    initialRows: Int = 36,
    initialCols: Int = 80,
    private val maxScrollback: Int = 1500
) {
    private val lock = ReentrantLock()

    private var _rows: Int = initialRows.coerceAtLeast(5)
    val rows: Int
        get() = lock.withLock { _rows }

    private var _cols: Int = initialCols.coerceAtLeast(20)
    val cols: Int
        get() = lock.withLock { _cols }

    private val history = ArrayDeque<TerminalLine>()
    private var screen = Array(_rows) { TerminalLine(_cols) }

    private var _curRow: Int = 0
    val curRow: Int
        get() = lock.withLock { _curRow }

    private var _curCol: Int = 0
    val curCol: Int
        get() = lock.withLock { _curCol }

    // Current SGR state
    private var currentFg: Color? = null
    private var currentBg: Color? = null
    private var currentBold: Boolean = false
    private var currentUnderline: Boolean = false

    // CSI parser state
    private var parserState = ParserState.NORMAL
    private val csiBuffer = StringBuilder()

    private var _versionCounter: Long = 0L
    val versionCounter: Long
        get() = lock.withLock { _versionCounter }

    val totalLines: Int
        get() = lock.withLock { history.size + _rows }

    private enum class ParserState {
        NORMAL, ESCAPE, CSI, OSC, OSC_ESC
    }

    fun lineAt(absIndex: Int): TerminalLine = lock.withLock {
        if (absIndex < 0) return@withLock TerminalLine(_cols)
        val histSize = history.size
        if (absIndex < histSize) {
            history.getOrNull(absIndex) ?: TerminalLine(_cols)
        } else {
            val screenIdx = absIndex - histSize
            screen.getOrNull(screenIdx) ?: TerminalLine(_cols)
        }
    }

    fun appendBytes(bytes: ByteArray, palette: WabiSabiPalette): Unit = lock.withLock {
        val text = bytes.toString(Charsets.UTF_8)
        for (ch in text) {
            processChar(ch, palette)
        }
        _versionCounter++
    }

    private fun safeScreenLine(row: Int): TerminalLine {
        val clamped = row.coerceIn(0, (_rows - 1).coerceAtLeast(0))
        return screen.getOrNull(clamped) ?: TerminalLine(_cols).also {
            if (clamped < screen.size) screen[clamped] = it
        }
    }

    private fun processChar(ch: Char, palette: WabiSabiPalette) {
        when (parserState) {
            ParserState.NORMAL -> {
                when (ch) {
                    AnsiConstants.ESC -> parserState = ParserState.ESCAPE
                    AnsiConstants.CR -> _curCol = 0
                    AnsiConstants.LF -> {
                        _curCol = 0
                        lineFeed()
                    }
                    AnsiConstants.BS, AnsiConstants.DEL -> handleBackspace()
                    AnsiConstants.TAB -> {
                        val nextStop = ((_curCol / 4) + 1) * 4
                        while (_curCol < nextStop && _curCol < _cols) {
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
     * Proper VT100/ANSI Backspace behavior across soft-wrapped lines.
     */
    private fun handleBackspace() {
        if (_curCol > 0) {
            _curCol--
        } else if (_curRow > 0 && screen.getOrNull(_curRow - 1)?.softWrapped == true) {
            _curRow--
            _curCol = (_cols - 1).coerceAtLeast(0)
        }
    }

    private fun putChar(ch: Char) {
        if (_curCol >= _cols) {
            safeScreenLine(_curRow).softWrapped = true
            _curCol = 0
            lineFeed()
        }
        val line = safeScreenLine(_curRow)
        if (_curCol in line.cells.indices) {
            line.cells[_curCol] = TermCell(
                char = ch,
                fgColor = currentFg,
                bgColor = currentBg,
                bold = currentBold,
                underline = currentUnderline
            )
        }
        _curCol++
    }

    private fun lineFeed() {
        if (_curRow < _rows - 1) {
            _curRow++
        } else {
            screen.getOrNull(0)?.let { history.addLast(it) }
            while (history.size > maxScrollback) {
                history.removeFirst()
            }
            for (r in 0 until _rows - 1) {
                screen[r] = screen.getOrNull(r + 1) ?: TerminalLine(_cols)
            }
            if (_rows - 1 in screen.indices) {
                screen[_rows - 1] = TerminalLine(_cols)
            }
        }
    }

    private fun resetScreen() {
        screen = Array(_rows) { TerminalLine(_cols) }
        _curRow = 0
        _curCol = 0
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
                val r = (parts.getOrNull(0) ?: 1).coerceIn(1, _rows) - 1
                val c = (parts.getOrNull(1) ?: 1).coerceIn(1, _cols) - 1
                _curRow = r
                _curCol = c
            }
            'A' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                _curRow = (_curRow - n).coerceAtLeast(0)
            }
            'B' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                _curRow = (_curRow + n).coerceAtMost(_rows - 1)
            }
            'C' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                _curCol = (_curCol + n).coerceAtMost(_cols - 1)
            }
            'D' -> {
                val n = (parts.firstOrNull() ?: 1).coerceAtLeast(1)
                if (_curCol - n >= 0) {
                    _curCol -= n
                } else if (_curRow > 0 && screen.getOrNull(_curRow - 1)?.softWrapped == true) {
                    val rem = n - _curCol
                    _curRow--
                    _curCol = (_cols - rem).coerceIn(0, _cols - 1)
                } else {
                    _curCol = 0
                }
            }
            'J' -> {
                val mode = parts.firstOrNull() ?: 0
                when (mode) {
                    2, 3 -> {
                        for (r in 0 until _rows) {
                            if (r in screen.indices) screen[r] = TerminalLine(_cols)
                        }
                        _curRow = 0
                        _curCol = 0
                    }
                    0 -> {
                        val line = safeScreenLine(_curRow)
                        for (c in _curCol until _cols) {
                            if (c in line.cells.indices) line.cells[c] = TermCell()
                        }
                        for (r in _curRow + 1 until _rows) {
                            if (r in screen.indices) screen[r] = TerminalLine(_cols)
                        }
                    }
                }
            }
            'K' -> {
                val mode = parts.firstOrNull() ?: 0
                val line = safeScreenLine(_curRow)
                when (mode) {
                    0 -> for (c in _curCol until _cols) {
                        if (c in line.cells.indices) line.cells[c] = TermCell()
                    }
                    1 -> for (c in 0.._curCol.coerceAtMost(_cols - 1)) {
                        if (c in line.cells.indices) line.cells[c] = TermCell()
                    }
                    2 -> {
                        for (c in 0 until _cols) {
                            if (c in line.cells.indices) line.cells[c] = TermCell()
                        }
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
                in 30..37 -> currentFg = palette.ansiColors.getOrNull(code - 30)
                39 -> currentFg = null
                in 40..47 -> currentBg = palette.ansiColors.getOrNull(code - 40)
                49 -> currentBg = null
                in 90..97 -> currentFg = palette.ansiColors.getOrNull(code - 90 + 8)
                in 100..107 -> currentBg = palette.ansiColors.getOrNull(code - 100 + 8)
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
        if (targetRows == _rows && targetCols == _cols) return

        history.forEach { it.resize(targetCols) }
        val newScreen = Array(targetRows) { r ->
            screen.getOrNull(r)?.apply { resize(targetCols) } ?: TerminalLine(targetCols)
        }
        screen = newScreen
        _rows = targetRows
        _cols = targetCols
        _curRow = _curRow.coerceIn(0, _rows - 1)
        _curCol = _curCol.coerceIn(0, _cols - 1)
        _versionCounter++
    }

    fun snapshotScreen(
        palette: WabiSabiPalette,
        autoCommandColors: Boolean
    ): Pair<List<Array<TermCell>>, Pair<Int, Int>> = lock.withLock {
        val out = ArrayList<Array<TermCell>>(_rows)
        for (r in 0 until _rows) {
            val lineObj = screen.getOrNull(r) ?: TerminalLine(_cols)
            val rawCells = Array(_cols) { c -> lineObj.cells.getOrNull(c) ?: TermCell() }
            if (autoCommandColors) {
                applySemanticColoring(rawCells, palette)
            }
            out.add(rawCells)
        }
        out to (_curRow.coerceIn(0, _rows - 1) to _curCol.coerceIn(0, _cols - 1))
    }

    private fun applySemanticColoring(cells: Array<TermCell>, palette: WabiSabiPalette) {
        val lineStr = String(CharArray(cells.size) { cells[it].char })
        val lower = lineStr.lowercase()

        for (errKw in AnsiConstants.ERROR_KEYWORDS) {
            val idx = lower.indexOf(errKw)
            if (idx >= 0) {
                for (c in idx until (idx + errKw.length).coerceAtMost(cells.size)) {
                    cells[c] = cells[c].copy(fgColor = Sumi.Kurenai, bold = true)
                }
            }
        }

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
                for (c in tokenStart until tokenEnd.coerceAtMost(cells.size)) {
                    cells[c] = cells[c].copy(fgColor = color, bold = true)
                }
                for (c in tokenEnd until lineStr.length.coerceAtMost(cells.size)) {
                    if (cells[c].fgColor == null) {
                        cells[c] = cells[c].copy(fgColor = Sumi.Shironeri)
                    }
                }
            }
        }
    }

    fun getLineString(row: Int): String = lock.withLock {
        val lineObj = screen.getOrNull(row) ?: return@withLock ""
        String(CharArray(_cols) { c -> lineObj.cells.getOrNull(c)?.char ?: ' ' })
    }

    fun selectAll(): TerminalSelection = lock.withLock {
        var firstRow = 0
        var lastRow = (_rows - 1).coerceAtLeast(0)
        var foundNonEmpty = false

        for (r in 0 until _rows) {
            val line = screen.getOrNull(r) ?: continue
            if (line.cells.any { it.char != ' ' }) {
                firstRow = r
                foundNonEmpty = true
                break
            }
        }

        if (!foundNonEmpty) {
            return@withLock TerminalSelection(
                startRow = 0,
                startCol = 0,
                endRow = (_rows - 1).coerceAtLeast(0),
                endCol = (_cols - 1).coerceAtLeast(0)
            )
        }

        for (r in (_rows - 1) downTo firstRow) {
            val line = screen.getOrNull(r) ?: continue
            if (line.cells.any { it.char != ' ' }) {
                lastRow = r
                break
            }
        }

        var lastCol = (_cols - 1).coerceAtLeast(0)
        val lastLineCells = screen.getOrNull(lastRow)?.cells
        if (lastLineCells != null) {
            for (c in (lastLineCells.size - 1) downTo 0) {
                if (lastLineCells[c].char != ' ') {
                    lastCol = c
                    break
                }
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
        val maxRow = (_rows - 1).coerceAtLeast(0)
        for (r in norm.startRow..norm.endRow.coerceAtMost(maxRow)) {
            if (r < 0) continue
            val line = screen.getOrNull(r)?.cells ?: continue
            val cStart = if (r == norm.startRow) norm.startCol.coerceIn(0, (_cols - 1).coerceAtLeast(0)) else 0
            val cEnd = if (r == norm.endRow) norm.endCol.coerceIn(0, (_cols - 1).coerceAtLeast(0)) else (_cols - 1).coerceAtLeast(0)
            if (cEnd >= cStart) {
                val chunk = StringBuilder()
                for (c in cStart..cEnd) {
                    chunk.append(line.getOrNull(c)?.char ?: ' ')
                }
                sb.append(chunk.toString().trimEnd())
            }
            if (r < norm.endRow) sb.append('\n')
        }
        sb.toString().trimEnd()
    }
}
