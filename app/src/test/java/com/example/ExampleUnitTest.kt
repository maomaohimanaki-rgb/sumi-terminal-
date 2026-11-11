package com.example

import dev.sumi.terminal.terminal.KeyEncoder
import dev.sumi.terminal.terminal.TerminalBuffer
import dev.sumi.terminal.terminal.TerminalSelection
import dev.sumi.terminal.ui.theme.Sumi
import dev.sumi.terminal.ui.theme.SumiPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun wordMagnet_snapsWithinHysteresisThreshold() {
        val line = "sumi@arch:~$ pacman -Syu --noconfirm"
        // "pacman" starts at col 13 and ends at col 18
        val (snappedStart, didSnapStart) = TerminalSelection.snapColumnWithWordMagnet(
            line = line,
            rawColFloat = 11.6f, // 1.4 cells away from 'p' at index 13 (<= 1.8 threshold)
            isStartHandle = true
        )
        assertTrue(didSnapStart)
        assertEquals(13, snappedStart)

        val (snappedEnd, didSnapEnd) = TerminalSelection.snapColumnWithWordMagnet(
            line = line,
            rawColFloat = 19.5f, // 1.5 cells away from 'n' at index 18 (<= 1.8 threshold)
            isStartHandle = false
        )
        assertTrue(didSnapEnd)
        assertEquals(18, snappedEnd)
    }

    @Test
    fun terminalBuffer_backspaceWrapsAcrossSoftWrappedLine() {
        val buffer = TerminalBuffer(initialRows = 10, initialCols = 20)
        val longLine = "12345678901234567890ABC" // 23 chars -> wraps at col 20 to row 1 col 3
        buffer.appendBytes(longLine.toByteArray(Charsets.UTF_8), SumiPalettes.Carvao)
        assertEquals(1, buffer.curRow)
        assertEquals(3, buffer.curCol)

        // Backspace 4 times -> should wrap back to row 0, col 19
        buffer.appendBytes(byteArrayOf(0x08, 0x08, 0x08, 0x08), SumiPalettes.Carvao)
        assertEquals(0, buffer.curRow)
        assertEquals(19, buffer.curCol)
    }

    @Test
    fun semanticColoring_highlightsFirstExecutableAndKurenaiErrors() {
        val buffer = TerminalBuffer(initialRows = 6, initialCols = 60)
        buffer.appendBytes(
            "sumi@arch:~$ cargo build --release\r\nfatal: repository not found".toByteArray(Charsets.UTF_8),
            SumiPalettes.Carvao
        )
        val (rows, _) = buffer.snapshotScreen(SumiPalettes.Carvao, autoCommandColors = true)
        // 'c' of "cargo" is at index 13 on row 0
        assertEquals(SumiPalettes.Carvao.commandHighlight, rows[0][13].fgColor)
        // 'f' of "fatal:" is at index 0 on row 1
        assertEquals(Sumi.Kurenai, rows[1][0].fgColor)
    }

    @Test
    fun bracketedPaste_wrapsMultilineBlockSafely() {
        val raw = "echo hello\r\necho world"
        val encoded = KeyEncoder.wrapBracketedPaste(raw, bracketed = true).toString(Charsets.UTF_8)
        assertEquals("\u001B[200~echo hello\necho world\u001B[201~", encoded)
    }
}
