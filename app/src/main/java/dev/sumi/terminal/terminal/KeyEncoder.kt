package dev.sumi.terminal.terminal

object KeyEncoder {

    private val shiftedSymbolMap = mapOf(
        '`' to '~',
        '1' to '!',
        '2' to '@',
        '3' to '#',
        '4' to '$',
        '5' to '%',
        '6' to '^',
        '7' to '&',
        '8' to '*',
        '9' to '(',
        '0' to ')',
        '-' to '_',
        '=' to '+',
        '[' to '{',
        ']' to '}',
        '\\' to '|',
        ';' to ':',
        '\'' to '"',
        ',' to '<',
        '.' to '>',
        '/' to '?'
    )

    /**
     * Xterm modifier code: 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0)
     */
    fun xtermModifierCode(mods: Mods): Int {
        return 1 +
                (if (mods.shift) 1 else 0) +
                (if (mods.alt) 2 else 0) +
                (if (mods.ctrl) 4 else 0)
    }

    fun encode(key: TermKey, mods: Mods = Mods()): ByteArray {
        val modCode = xtermModifierCode(mods)
        val hasMod = modCode > 1

        val raw: String = when (key) {
            TermKey.ESC -> "\u001B"
            TermKey.TAB -> if (mods.shift) "\u001B[Z" else "\t"
            TermKey.ENTER -> "\r"
            TermKey.BACKSPACE -> if (mods.ctrl) "\u0008" else "\u007F"
            TermKey.UP -> if (hasMod) "\u001B[1;${modCode}A" else "\u001B[A"
            TermKey.DOWN -> if (hasMod) "\u001B[1;${modCode}B" else "\u001B[B"
            TermKey.RIGHT -> if (hasMod) "\u001B[1;${modCode}C" else "\u001B[C"
            TermKey.LEFT -> if (hasMod) "\u001B[1;${modCode}D" else "\u001B[D"
            TermKey.HOME -> if (hasMod) "\u001B[1;${modCode}H" else "\u001B[H"
            TermKey.END -> if (hasMod) "\u001B[1;${modCode}F" else "\u001B[F"
            TermKey.PGUP -> if (hasMod) "\u001B[5;${modCode}~" else "\u001B[5~"
            TermKey.PGDN -> if (hasMod) "\u001B[6;${modCode}~" else "\u001B[6~"
            TermKey.DEL -> if (hasMod) "\u001B[3;${modCode}~" else "\u001B[3~"
            TermKey.CTRL_C -> "\u0003"
            TermKey.CTRL_D -> "\u0004"
            TermKey.CTRL_L -> "\u000C"
            TermKey.CTRL_Z -> "\u001A"
        }

        // If only Alt is pressed on a non-CSI key (like Tab or Enter), prefix with ESC
        return if (mods.alt && !raw.startsWith("\u001B")) {
            ("\u001B" + raw).toByteArray(Charsets.UTF_8)
        } else {
            raw.toByteArray(Charsets.UTF_8)
        }
    }

    fun applyShiftTransform(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val mapped = shiftedSymbolMap[ch] ?: ch.uppercaseChar()
            sb.append(mapped)
        }
        return sb.toString()
    }

    fun encodeText(text: String, mods: Mods = Mods()): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val effectiveText = if (mods.shift) applyShiftTransform(text) else text

        if (mods.ctrl && effectiveText.length == 1) {
            val ch = effectiveText[0].uppercaseChar()
            if (ch in 'A'..'Z') {
                val ctrlCode = (ch.code - 'A'.code + 1).toByte()
                return if (mods.alt) byteArrayOf(0x1B, ctrlCode) else byteArrayOf(ctrlCode)
            }
        }
        val payload = if (mods.alt) "\u001B$effectiveText" else effectiveText
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun wrapBracketedPaste(text: String, bracketed: Boolean): ByteArray {
        val sanitized = text.replace("\r\n", "\n")
        return if (bracketed) {
            (AnsiConstants.BRACKETED_PASTE_START + sanitized + AnsiConstants.BRACKETED_PASTE_END)
                .toByteArray(Charsets.UTF_8)
        } else {
            sanitized.toByteArray(Charsets.UTF_8)
        }
    }
}
