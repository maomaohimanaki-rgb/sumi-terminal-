package com.example

import dev.sumi.terminal.files.FileOps
import dev.sumi.terminal.terminal.KeyEncoder
import dev.sumi.terminal.terminal.Mods
import dev.sumi.terminal.terminal.TermKey
import dev.sumi.terminal.terminal.TerminalBuffer
import dev.sumi.terminal.terminal.TerminalSelection
import dev.sumi.terminal.ui.theme.Sumi
import dev.sumi.terminal.ui.theme.SumiPalettes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ExampleUnitTest {

    @Test
    fun softHysteresisMagnet_snapsNearEdgeAndReleasesImmediatelyOnDrag() {
        val line = "root@arch:~# pacman -Syu --noconfirm"
        // "pacman" starts at col 13 and ends at col 18
        val (snappedStart, didSnapStart) = TerminalSelection.snapColumnWithWordMagnet(
            line = line,
            rawColFloat = 12.75f, // 0.25 cells from index 13 (<= 0.42 threshold) -> snaps
            isStartHandle = true
        )
        assertTrue(didSnapStart)
        assertEquals(13, snappedStart)

        // When user drags past the word into the next word ("-Syu" at col 20..23), magnet releases immediately!
        val (freeCol, didSnapMid) = TerminalSelection.snapColumnWithWordMagnet(
            line = line,
            rawColFloat = 22.0f, // Middle of "-Syu", not trapped on "pacman"
            isStartHandle = false
        )
        assertFalse(didSnapMid)
        assertEquals(22, freeCol)
    }

    @Test
    fun xtermShiftEncoding_producesExactAnsiSequences() {
        val shiftMod = Mods(shift = true)
        assertEquals("\u001B[Z", KeyEncoder.encode(TermKey.TAB, shiftMod).toString(Charsets.UTF_8))
        assertEquals("\u001B[1;2A", KeyEncoder.encode(TermKey.UP, shiftMod).toString(Charsets.UTF_8))
        assertEquals("\u001B[1;2B", KeyEncoder.encode(TermKey.DOWN, shiftMod).toString(Charsets.UTF_8))
        assertEquals("\u001B[1;2C", KeyEncoder.encode(TermKey.RIGHT, shiftMod).toString(Charsets.UTF_8))
        assertEquals("\u001B[1;2D", KeyEncoder.encode(TermKey.LEFT, shiftMod).toString(Charsets.UTF_8))

        val ctrlShiftMod = Mods(ctrl = true, shift = true) // 1 + 1 + 4 = 6
        assertEquals("\u001B[1;6A", KeyEncoder.encode(TermKey.UP, ctrlShiftMod).toString(Charsets.UTF_8))

        // Text with Shift active capitalizes and shifts symbols
        assertEquals("ABC!", KeyEncoder.encodeText("abc1", shiftMod).toString(Charsets.UTF_8))
    }

    @Test
    fun terminalBuffer_backspaceWrapsAcrossSoftWrappedLine() {
        val buffer = TerminalBuffer(initialRows = 10, initialCols = 20)
        val longLine = "12345678901234567890ABC"
        buffer.appendBytes(longLine.toByteArray(Charsets.UTF_8), SumiPalettes.Carvao)
        assertEquals(1, buffer.curRow)
        assertEquals(3, buffer.curCol)

        buffer.appendBytes(byteArrayOf(0x08, 0x08, 0x08, 0x08), SumiPalettes.Carvao)
        assertEquals(0, buffer.curRow)
        assertEquals(19, buffer.curCol)
    }

    @Test
    fun crossFilesystemFileOps_copiesAndMovesRecursively() {
        runBlocking {
            val tmpRoot = createTempDirectory("sumi_fs_test").toFile()
            val srcDir = File(tmpRoot, "sdcard_download").apply { mkdirs() }
            val dstDir = File(tmpRoot, "arch_rootfs_root").apply { mkdirs() }

            val sampleFile = File(srcDir, "payload.sh").apply {
                writeText("#!/bin/bash\necho ok\n")
            }

            val copied = FileOps.copyBatchCrossFilesystem(listOf(sampleFile), dstDir).getOrThrow()
            assertEquals(1, copied)
            assertTrue(File(dstDir, "payload.sh").exists())
            assertTrue(sampleFile.exists())

            val dstMoveDir = File(tmpRoot, "arch_moved").apply { mkdirs() }
            val moved = FileOps.moveBatchCrossFilesystem(listOf(sampleFile), dstMoveDir).getOrThrow()
            assertEquals(1, moved)
            val movedFile = File(dstMoveDir, "payload.sh")
            assertTrue(movedFile.exists())
            assertFalse(sampleFile.exists())

            val renamedFile = FileOps.rename(movedFile, "deploy.sh").getOrThrow()
            assertEquals("deploy.sh", renamedFile.name)
            assertTrue(renamedFile.exists())
            assertFalse(movedFile.exists())

            tmpRoot.deleteRecursively()
        }
    }

    @Test
    fun terminalBuffer_selectAll_and_dualHandleSelection() {
        val buffer = TerminalBuffer(initialRows = 10, initialCols = 40)
        buffer.appendBytes("root@arch:~# uname -a\r\nLinux localhost 6.6-arch1 aarch64".toByteArray(Charsets.UTF_8), SumiPalettes.Carvao)

        val allSel = buffer.selectAll()
        assertEquals(0, allSel.startRow)
        assertEquals(0, allSel.startCol)
        assertEquals(1, allSel.endRow)
        val extractedAll = buffer.extract(allSel)
        assertTrue(extractedAll.contains("uname -a"))
        assertTrue(extractedAll.contains("aarch64"))

        // Moving left (start) handle independently from col 13 to col 0 while right handle stays at col 20
        val movedLeftHandle = TerminalSelection(startRow = 0, startCol = 0, endRow = 0, endCol = 20)
        assertEquals("root@arch:~# uname -a", buffer.extract(movedLeftHandle))
    }

    @Test
    fun terminalBuffer_lfResetsColumnToZero_preventingStaircaseEffect() {
        val buffer = TerminalBuffer(initialRows = 10, initialCols = 40)
        // Bare \n (0x0A) without \r (0x0D) must reset curCol to 0 on the next line
        buffer.appendBytes("linha1\nlinha2\nlinha3".toByteArray(Charsets.UTF_8), SumiPalettes.Carvao)
        assertEquals(2, buffer.curRow)
        assertEquals(6, buffer.curCol)
        assertEquals("linha1", buffer.getLineString(0).trimEnd())
        assertEquals("linha2", buffer.getLineString(1).trimEnd())
        assertEquals("linha3", buffer.getLineString(2).trimEnd())
    }

    @Test
    fun imeDelta_eliminatesComposingTextMultiplication_and_enterLeakage() {
        val sequence = listOf("l", "lk", "lkj", "lkjh", "lkjhg", "lkjhgf", "lkjhgfd", "lkjhgfds", "lkjhgfdsa")
        var composingBuffer = ""
        val emitted = StringBuilder()

        // Simulate Gboard sending cumulative setComposingText on each keystroke
        for (composing in sequence) {
            val delta = dev.sumi.terminal.ui.components.computeImeDelta(
                oldComposing = composingBuffer,
                newComposing = composing
            )
            composingBuffer = composing
            emitted.append(delta.textToSend)
        }

        // Strict 1-to-1 emission: typing l,k,j,h,g,f,d,s,a emits strictly "lkjhgfdsa" (never "llklkj...")
        assertEquals("lkjhgfdsa", emitted.toString())

        // Simulate Gboard calling commitText("lkjhgfdsa") right after setComposingText("lkjhgfdsa")
        val commitDelta = dev.sumi.terminal.ui.components.computeImeDelta(
            oldComposing = composingBuffer,
            newComposing = "lkjhgfdsa"
        )
        composingBuffer = ""
        assertEquals(0, commitDelta.backspaces)
        assertEquals("", commitDelta.textToSend)

        // Simulate Enter clearing composingBuffer, followed by typing "cd" on the new prompt
        composingBuffer = ""
        val nextPromptEmitted = StringBuilder()
        for (composing in listOf("c", "cd")) {
            val delta = dev.sumi.terminal.ui.components.computeImeDelta(
                oldComposing = composingBuffer,
                newComposing = composing
            )
            composingBuffer = composing
            nextPromptEmitted.append(delta.textToSend)
        }
        assertEquals("cd", nextPromptEmitted.toString())
    }

    @Test
    fun tuiGlyphClassification_and_blockArtColumnAlignment() {
        // Basic ASCII (0x20..0x7E) can coalesce; Box Drawing (U+2500..U+257F), Block Elements (U+2580..U+259F),
        // and TUI symbols (•, ❯, ✻, ▮) must never coalesce and are rendered geometrically in exact cell bounds
        assertTrue(dev.sumi.terminal.ui.components.TerminalGlyphRenderer.isBasicAscii('A'))
        assertFalse(dev.sumi.terminal.ui.components.TerminalGlyphRenderer.isBasicAscii('─'))
        assertFalse(dev.sumi.terminal.ui.components.TerminalGlyphRenderer.isBasicAscii('█'))
        assertFalse(dev.sumi.terminal.ui.components.TerminalGlyphRenderer.isBasicAscii('✻'))

        for (code in 0x2500..0x259F) {
            assertTrue(dev.sumi.terminal.ui.components.TerminalGlyphRenderer.isGeometricGlyph(code.toChar()))
        }
        for (sym in charArrayOf('•', '❯', '✻', '▮')) {
            assertTrue(dev.sumi.terminal.ui.components.TerminalGlyphRenderer.isGeometricGlyph(sym))
        }

        // Shift+Tab direct key and modifier encoding
        assertEquals("\u001B[Z", KeyEncoder.encode(TermKey.SHIFT_TAB).toString(Charsets.UTF_8))
        assertEquals("\t", KeyEncoder.encode(TermKey.TAB).toString(Charsets.UTF_8))
        assertEquals("\u000C", KeyEncoder.encode(TermKey.CTRL_L).toString(Charsets.UTF_8))

        // Verify 24-bit colored block art + CHA cursor horizontal absolute (CSI G) aligns to exact grid columns
        val buffer = TerminalBuffer(initialRows = 10, initialCols = 60)
        val bannerLine1 = "\u001B[38;2;215;119;87m ▐▛███▜▌ \u001B[0m\u001B[12GClaude Code v2.1"
        val bannerLine2 = "\r\n\u001B[38;2;215;119;87m▝▜█████▛▘\u001B[0m\u001B[12GSonnet 4.6 · Arch Linux"
        val bannerLine3 = "\r\n\u001B[38;2;215;119;87m  ▘▘ ▝▝  \u001B[0m\u001B[12G~/workspace"
        buffer.appendBytes((bannerLine1 + bannerLine2 + bannerLine3).toByteArray(Charsets.UTF_8), SumiPalettes.Carvao)

        assertEquals(" ▐▛███▜▌   Claude Code v2.1", buffer.getLineString(0).trimEnd())
        assertEquals("▝▜█████▛▘  Sonnet 4.6 · Arch Linux", buffer.getLineString(1).trimEnd())
        assertEquals("  ▘▘ ▝▝    ~/workspace", buffer.getLineString(2).trimEnd())

        // Verify 24-bit color (215, 119, 87) is preserved on block art cells and not overwritten by semantic coloring
        val (snapshot, _) = buffer.snapshotScreen(SumiPalettes.Carvao, autoCommandColors = true)
        val blockCell = snapshot[0][2] // '▛'
        assertEquals('▛', blockCell.char)
        assertEquals(androidx.compose.ui.graphics.Color(215, 119, 87), blockCell.fgColor)
    }
}
