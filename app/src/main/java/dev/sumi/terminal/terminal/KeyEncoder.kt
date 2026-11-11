package dev.sumi.terminal.terminal

object KeyEncoder {

    private val shiftedSymbolMap = mapOf(
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
        '/' to '?',
        '`' to '~'
    )

    private fun xtermModifierCode(mods: Mods): Int {
        var code = 1
        if (mods.shift) code += 1
        if (mods.alt) code += 2
        if (mods.ctrl) code += 4
        return code
    }

    fun encode(key: TermKey, mods: Mods = Mods()): ByteArray {
        val modCode = xtermModifierCode(mods)
        val seq: String = when (key) {
            TermKey.ENTER -> "\r"
            TermKey.BACKSPACE -> "\u007F"
            TermKey.DEL -> if (modCode > 1) "\u001B[3;${modCode}~" else "\u001B[3~"
            TermKey.TAB -> if (mods.shift) "\u001B[Z" else "\t"
            TermKey.ESC -> "\u001B"
            TermKey.UP -> if (modCode > 1) "\u001B[1;${modCode}A" else "\u001B[A"
            TermKey.DOWN -> if (modCode > 1) "\u001B[1;${modCode}B" else "\u001B[B"
            TermKey.RIGHT -> if (modCode > 1) "\u001B[1;${modCode}C" else "\u001B[C"
            TermKey.LEFT -> if (modCode > 1) "\u001B[1;${modCode}D" else "\u001B[D"
            TermKey.HOME -> if (modCode > 1) "\u001B[1;${modCode}H" else "\u001B[H"
            TermKey.END -> if (modCode > 1) "\u001B[1;${modCode}F" else "\u001B[F"
            TermKey.PGUP -> if (modCode > 1) "\u001B[5;${modCode}~" else "\u001B[5~"
            TermKey.PGDN -> if (modCode > 1) "\u001B[6;${modCode}~" else "\u001B[6~"
            TermKey.CTRL_C -> "\u0003"
            TermKey.CTRL_D -> "\u0004"
            TermKey.CTRL_Z -> "\u001A"
            TermKey.CTRL_L -> "\u000C"
        }
        return seq.toByteArray(Charsets.UTF_8)
    }

    private fun applyShiftTransform(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when {
                ch in 'a'..'z' -> sb.append(ch.uppercaseChar())
                shiftedSymbolMap.containsKey(ch) -> sb.append(shiftedSymbolMap.getValue(ch))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun encodeText(text: String, mods: Mods = Mods()): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        val shifted = if (mods.shift) applyShiftTransform(text) else text

        if (mods.ctrl && shifted.length == 1) {
            val ch = shifted[0].uppercaseChar()
            if (ch in 'A'..'Z') {
                val ctrlByte = (ch.code - 'A'.code + 1).toByte()
                return if (mods.alt) {
                    byteArrayOf(0x1B, ctrlByte)
                } else {
                    byteArrayOf(ctrlByte)
                }
            }
        }

        val payload = if (mods.alt) "\u001B$shifted" else shifted
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun wrapBracketedPaste(text: String, bracketed: Boolean): ByteArray {
        val normalized = text.replace("\r\n", "\n")
        val wrapped = if (bracketed) {
            "${AnsiConstants.BRACKETED_PASTE_START}$normalized${AnsiConstants.BRACKETED_PASTE_END}"
        } else {
            normalized
        }
        return wrapped.toByteArray(Charsets.UTF_8)
    }
}
