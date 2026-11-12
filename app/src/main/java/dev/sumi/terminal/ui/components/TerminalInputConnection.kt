package dev.sumi.terminal.ui.components

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.sumi.terminal.terminal.TermKey

internal data class ImeDelta(
    val backspaces: Int,
    val textToSend: String
)

internal fun computeImeDelta(oldComposing: String, newComposing: String): ImeDelta {
    var commonPrefix = 0
    val minLen = minOf(oldComposing.length, newComposing.length)
    while (commonPrefix < minLen && oldComposing[commonPrefix] == newComposing[commonPrefix]) {
        commonPrefix++
    }
    val backspaces = oldComposing.length - commonPrefix
    val textToSend = newComposing.substring(commonPrefix)
    return ImeDelta(backspaces = backspaces, textToSend = textToSend)
}

/**
 * Direct, non-composing Native View subclass for terminal IME capture.
 * Uses TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS so virtual
 * keyboards never autocomplete, autocorrect, or hold a composing buffer.
 */
class TerminalInputConnectionView(
    context: Context,
    var onSendBytes: (ByteArray) -> Unit = {},
    var onKey: (TermKey) -> Unit = {},
    var onRawText: (String) -> Unit = {},
    var onInterceptPaste: (String) -> Unit = {}
) : View(context) {

    private var activeConnection: BaseInputConnection? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun openKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun resetImeState() {
        runCatching {
            activeConnection?.finishComposingText()
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        val conn = object : BaseInputConnection(this, false) {

            private fun sendDirectText(raw: String) {
                if (raw.isEmpty()) return
                if (raw.length > 80 || raw.count { it == '\n' } > 1) {
                    onInterceptPaste(raw)
                    return
                }
                val sb = StringBuilder()
                for (ch in raw) {
                    if (ch == '\n' || ch == '\r') {
                        if (sb.isNotEmpty()) {
                            onRawText(sb.toString())
                            sb.clear()
                        }
                        onKey(TermKey.ENTER)
                    } else {
                        sb.append(ch)
                    }
                }
                if (sb.isNotEmpty()) {
                    onRawText(sb.toString())
                }
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (str.isNotEmpty()) {
                    sendDirectText(str)
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (str.isNotEmpty()) {
                    sendDirectText(str)
                }
                super.finishComposingText()
                return true
            }

            override fun finishComposingText(): Boolean {
                return super.finishComposingText()
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                onKey(TermKey.ENTER)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        onKey(TermKey.BACKSPACE)
                    }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> onKey(TermKey.ENTER)
                        KeyEvent.KEYCODE_DEL -> onKey(TermKey.BACKSPACE)
                        KeyEvent.KEYCODE_FORWARD_DEL -> onKey(TermKey.DEL)
                        KeyEvent.KEYCODE_DPAD_UP -> onKey(TermKey.UP)
                        KeyEvent.KEYCODE_DPAD_DOWN -> onKey(TermKey.DOWN)
                        KeyEvent.KEYCODE_DPAD_LEFT -> onKey(TermKey.LEFT)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> onKey(TermKey.RIGHT)
                        KeyEvent.KEYCODE_MOVE_HOME -> onKey(TermKey.HOME)
                        KeyEvent.KEYCODE_MOVE_END -> onKey(TermKey.END)
                        KeyEvent.KEYCODE_PAGE_UP -> onKey(TermKey.PGUP)
                        KeyEvent.KEYCODE_PAGE_DOWN -> onKey(TermKey.PGDN)
                        KeyEvent.KEYCODE_TAB -> onKey(TermKey.TAB)
                        KeyEvent.KEYCODE_ESCAPE -> onKey(TermKey.ESC)
                        else -> {
                            if (event.isCtrlPressed && event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                                val ctrlByte = (event.keyCode - KeyEvent.KEYCODE_A + 1).toByte()
                                onSendBytes(byteArrayOf(ctrlByte))
                            } else {
                                val unicodeChar = event.unicodeChar
                                if (unicodeChar == '\n'.code || unicodeChar == '\r'.code) {
                                    onKey(TermKey.ENTER)
                                } else if (unicodeChar != 0) {
                                    onRawText(unicodeChar.toChar().toString())
                                }
                            }
                        }
                    }
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
        activeConnection = conn
        return conn
    }
}
