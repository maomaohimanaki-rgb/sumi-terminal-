package dev.sumi.terminal.terminal

import kotlin.math.abs
import kotlin.math.roundToInt

data class WordSpan(
    val startCol: Int,
    val endCol: Int,
    val text: String
)

/**
 * Represents a continuous range selection on the terminal grid.
 * Supports two distinct modes:
 * 1. Double-Tap -> selects complete word under pointer (`selectWordAt`).
 * 2. Long-Press & Handle Drag -> free continuous selection across characters, words, and lines
 *    with soft non-blocking hysteresis edge snapping (< 0.42 cells from word edge).
 */
data class TerminalSelection(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int
) {
    fun normalized(): TerminalSelection {
        return if (startRow < endRow || (startRow == endRow && startCol <= endCol)) {
            this
        } else {
            TerminalSelection(
                startRow = endRow,
                startCol = endCol,
                endRow = startRow,
                endCol = startCol
            )
        }
    }

    fun containsCell(row: Int, col: Int): Boolean {
        val n = normalized()
        if (row < n.startRow || row > n.endRow) return false
        if (n.startRow == n.endRow) return col in n.startCol..n.endCol
        if (row == n.startRow) return col >= n.startCol
        if (row == n.endRow) return col <= n.endCol
        return true
    }

    companion object {
        private val WORD_DELIMITERS = setOf(
            ' ', '\t', '|', ';', '&', '(', ')', '[', ']', '{', '}', '"', '\'', ':', '=', ','
        )

        fun extractWords(line: String): List<WordSpan> {
            val words = mutableListOf<WordSpan>()
            var idx = 0
            while (idx < line.length) {
                while (idx < line.length && line[idx] in WORD_DELIMITERS) {
                    idx++
                }
                if (idx >= line.length) break
                val start = idx
                while (idx < line.length && line[idx] !in WORD_DELIMITERS) {
                    idx++
                }
                val end = idx - 1
                if (end >= start) {
                    words.add(WordSpan(start, end, line.substring(start, idx)))
                }
            }
            return words
        }

        /**
         * Section 4: Soft Non-Blocking Hysteresis Magnet.
         * - If the user's pointer `rawColFloat` is within `magnetThresholdCells` (< 1 character,
         *   default 0.42f) of a word boundary, gently snaps to that boundary.
         * - As soon as the user drags past that threshold, the magnet releases immediately and
         *   follows the exact character cell `rawColFloat.roundToInt()`, NEVER locking selection
         *   to a single word!
         */
        fun snapColumnWithWordMagnet(
            line: String,
            rawColFloat: Float,
            isStartHandle: Boolean,
            magnetThresholdCells: Float = 0.42f
        ): Pair<Int, Boolean> {
            val maxCol = (line.length - 1).coerceAtLeast(0)
            val exactCol = rawColFloat.roundToInt().coerceIn(0, maxCol)
            val words = extractWords(line)
            if (words.isEmpty()) return exactCol to false

            var bestBoundary: Int? = null
            var bestDist = Float.MAX_VALUE

            for (w in words) {
                val boundary = if (isStartHandle) w.startCol else w.endCol
                val dist = abs(rawColFloat - boundary.toFloat())
                if (dist <= magnetThresholdCells && dist < bestDist) {
                    bestDist = dist
                    bestBoundary = boundary
                }
            }

            return if (bestBoundary != null) {
                bestBoundary.coerceIn(0, maxCol) to true
            } else {
                // Immediately release magnet so user can select arbitrary characters, words, or lines
                exactCol to false
            }
        }

        fun selectWordAt(line: String, row: Int, col: Int): TerminalSelection {
            val words = extractWords(line)
            val hit = words.firstOrNull { col in it.startCol..it.endCol }
            return if (hit != null) {
                TerminalSelection(row, hit.startCol, row, hit.endCol)
            } else {
                val c = col.coerceIn(0, (line.length - 1).coerceAtLeast(0))
                TerminalSelection(row, c, row, c)
            }
        }
    }
}
