package dev.sumi.terminal.terminal

import kotlin.math.abs

data class WordSpan(
    val startCol: Int,
    val endCol: Int,
    val text: String
)

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
            ' ', '\t', '|', ';', '&', '/', '(', ')', '[', ']', '{', '}', '"', '\'', ':', '=', ','
        )

        /**
         * Extracts all word tokens on a given line for magnetic snapping.
         */
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
         * Specification B: Hysteresis Word Magnet ("Ímã de Palavras").
         * If the floating-point column pointer `rawColFloat` is within `magnetThresholdCells`
         * (default 1.8 cells) of the start or end of a word on `line`, magnetically snaps the
         * handle to the word boundary and returns `(snappedCol, didSnapToWordBoundary)`.
         */
        fun snapColumnWithWordMagnet(
            line: String,
            rawColFloat: Float,
            isStartHandle: Boolean,
            magnetThresholdCells: Float = 1.8f
        ): Pair<Int, Boolean> {
            val maxCol = (line.length - 1).coerceAtLeast(0)
            val clampedRaw = rawColFloat.toInt().coerceIn(0, maxCol)
            val words = extractWords(line)
            if (words.isEmpty()) return clampedRaw to false

            var bestBoundary: Int? = null
            var bestDist = Float.MAX_VALUE

            for (w in words) {
                if (isStartHandle) {
                    val distStart = abs(rawColFloat - w.startCol.toFloat())
                    if (distStart <= magnetThresholdCells && distStart < bestDist) {
                        bestDist = distStart
                        bestBoundary = w.startCol
                    }
                } else {
                    val distEnd = abs(rawColFloat - w.endCol.toFloat())
                    if (distEnd <= magnetThresholdCells && distEnd < bestDist) {
                        bestDist = distEnd
                        bestBoundary = w.endCol
                    }
                }
            }

            if (bestBoundary != null) {
                return bestBoundary.coerceIn(0, maxCol) to true
            }

            // Also check if touch is directly inside a word on initial selection
            val containing = words.firstOrNull { clampedRaw in it.startCol..it.endCol }
            if (containing != null) {
                val distToBoundary = if (isStartHandle) {
                    abs(rawColFloat - containing.startCol)
                } else {
                    abs(rawColFloat - containing.endCol)
                }
                if (distToBoundary <= magnetThresholdCells) {
                    return (if (isStartHandle) containing.startCol else containing.endCol) to true
                }
            }

            return clampedRaw to false
        }

        fun selectWordAt(line: String, row: Int, col: Int): TerminalSelection {
            val words = extractWords(line)
            val hit = words.firstOrNull { col in it.startCol..it.endCol }
                ?: words.minByOrNull { minOf(abs(col - it.startCol), abs(col - it.endCol)) }
            return if (hit != null) {
                TerminalSelection(row, hit.startCol, row, hit.endCol)
            } else {
                val c = col.coerceIn(0, (line.length - 1).coerceAtLeast(0))
                TerminalSelection(row, c, row, c)
            }
        }
    }
}
