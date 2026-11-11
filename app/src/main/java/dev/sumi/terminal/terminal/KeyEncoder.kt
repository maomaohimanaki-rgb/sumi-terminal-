package dev.sumi.terminal.terminal

object KeyEncoder {
    fun encode(key: TermKey, mods: Mods = Mods()): ByteArray {
        val raw: String = when (key) {
            TermKey.ESC -> "\u001B"
            TermKey.TAB -> if (mods.shift) "\u001B[Z" else "\t"
            TermKey.ENTER -> "\r"
            TermKey.BACKSPACE -> "\u007F"
            TermKey.UP -> "\u001B[A"
            TermKey.DOWN -> "\u001B[B"
            TermKey.RIGHT -> "\u001B[C"
            TermKey.LEFT -> "\u001B[D"
            TermKey.HOME -> "\u001B[H"
            TermKey.END -> "\u001B[F"
            TermKey.PGUP -> "\u001B[5~"
            TermKey.PGDN -> "\u001B[6~"
            TermKey.DEL -> "\u001B[3~"
            TermKey.CTRL_C -> "\u0003"
            TermKey.CTRL_D -> "\u0004"
            TermKey.CTRL_L -> "\u000C"
            TermKey.CTRL_Z -> "\u001A"
        }
        return if (mods.alt && !raw.startsWith("\u001B")) {
            ("\u001B" + raw).toByteArray(Charsets.UTF_8)
        } else {
            raw.toByteArray(Charsets.UTF_8)
        }
    }

    fun encodeText(text: String, mods: Mods = Mods()): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        if (mods.ctrl && text.length == 1) {
            val ch = text[0].uppercaseChar()
            if (ch in 'A'..'Z') {
                val ctrlCode = (ch.code - 'A'.code + 1).toByte()
                return if (mods.alt) byteArrayOf(0x1B, ctrlCode) else byteArrayOf(ctrlCode)
            }
        }
        val payload = if (mods.alt) "\u001B$text" else text
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
