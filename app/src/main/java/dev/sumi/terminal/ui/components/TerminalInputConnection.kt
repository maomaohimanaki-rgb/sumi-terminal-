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

/**
 * Native View subclass replacing BasicTextField for terminal emulation.
 * Preserves Gboard/SwiftKey clipboard toolbar via TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS
 * while eliminating ghost commands, duplicate paste deltas, and broken multiline Backspace.
 */
class TerminalInputConnectionView(
    context: Context,
    var onSendBytes: (ByteArray) -> Unit = {},
    var onKey: (TermKey) -> Unit = {},
    var onRawText: (String) -> Unit = {},
    var onInterceptPaste: (String) -> Unit = {}
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun openKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text.isNullOrEmpty()) return true
                val str = text.toString()
                // Safe Paste interception: > 80 chars or contains newline
                if (str.length > 80 || str.contains('\n') || str.contains('\r')) {
                    onInterceptPaste(str)
                } else {
                    onRawText(str)
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    val str = text.toString()
                    if (str.length > 80 || str.contains('\n') || str.contains('\r')) {
                        onInterceptPaste(str)
                    } else {
                        onRawText(str)
                    }
                    finishComposingText()
                }
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
                                if (unicodeChar != 0) {
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
    }
}
