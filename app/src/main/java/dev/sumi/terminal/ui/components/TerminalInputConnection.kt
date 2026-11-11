package dev.sumi.terminal.ui.components

import android.content.Context
import android.text.Editable
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
 * Native View subclass for terminal IME capture.
 *
 * Eliminates "Composing Text Multiplication" ("l" -> "llk" -> "llklkj") and post-Enter
 * buffer leaks ("lkjhgfdsaclkjhgfdsacd") by:
 * 1. Configuring `TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`
 *    and `IME_FLAG_NO_PERSONALIZED_LEARNING` so Gboard disables cumulative word composition
 *    while keeping the top Gboard clipboard/utility bar active.
 * 2. Tracking exact character deltas (`composingBuffer`) across `setComposingText` and `commitText`
 *    so even if an IME insists on calling `setComposingText("lk")`, only the newly typed suffix `"k"`
 *    is transmitted to the PTY.
 * 3. Performing a hard IME session reset (`finishComposingText()` + `imm.restartInput(this)`)
 *    whenever Enter (`KEYCODE_ENTER`, `IME_ACTION_*`, or `\n`) is triggered so previous command
 *    buffers never leak into the next shell prompt.
 */
class TerminalInputConnectionView(
    context: Context,
    var onSendBytes: (ByteArray) -> Unit = {},
    var onKey: (TermKey) -> Unit = {},
    var onRawText: (String) -> Unit = {},
    var onInterceptPaste: (String) -> Unit = {}
) : View(context) {

    private var composingBuffer: String = ""
    private var activeConnection: InputConnection? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun openKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Hard-resets the IME composing state and restarts the input connection so Gboard
     * completely discards any accumulated word buffer (e.g., on Enter, Ctrl+C, or Tab).
     */
    fun resetImeState() {
        composingBuffer = ""
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        composingBuffer = ""
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

            private fun isCurrentConnection(): Boolean = (this === activeConnection)

            private fun dispatchDelta(newText: String, isCommit: Boolean) {
                // Multi-line or long clipboard paste (> 80 chars or multiple lines from clipboard)
                if (composingBuffer.isEmpty() && (newText.length > 80 || newText.count { it == '\n' } > 1)) {
                    onInterceptPaste(newText)
                    resetImeState()
                    return
                }

                // Check if the IME appended a newline at the end of a single-line input
                val hasTrailingNewline = newText.endsWith("\n") || newText.endsWith("\r")
                val cleanTarget = if (hasTrailingNewline) {
                    newText.trimEnd('\n', '\r')
                } else {
                    newText
                }

                val delta = computeImeDelta(composingBuffer, cleanTarget)
                if (delta.backspaces > 0) {
                    repeat(delta.backspaces) {
                        onKey(TermKey.BACKSPACE)
                    }
                }
                if (delta.textToSend.isNotEmpty()) {
                    onRawText(delta.textToSend)
                }

                if (hasTrailingNewline) {
                    composingBuffer = ""
                    onKey(TermKey.ENTER)
                    resetImeState()
                } else if (isCommit) {
                    composingBuffer = ""
                } else {
                    composingBuffer = cleanTarget
                }
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!isCurrentConnection()) return false
                val str = text?.toString().orEmpty()
                if (str.isEmpty() && composingBuffer.isEmpty()) return true
                dispatchDelta(str, isCommit = true)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!isCurrentConnection()) return false
                val str = text?.toString().orEmpty()
                dispatchDelta(str, isCommit = false)
                return true
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean {
                if (!isCurrentConnection()) return false
                composingBuffer = ""
                return true
            }

            override fun finishComposingText(): Boolean {
                if (!isCurrentConnection()) return false
                composingBuffer = ""
                return super.finishComposingText()
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                if (!isCurrentConnection()) return false
                composingBuffer = ""
                onKey(TermKey.ENTER)
                resetImeState()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!isCurrentConnection()) return false
                if (beforeLength > 0) {
                    if (composingBuffer.isNotEmpty()) {
                        val removeCount = beforeLength.coerceAtMost(composingBuffer.length)
                        composingBuffer = composingBuffer.dropLast(removeCount)
                    }
                    repeat(beforeLength) {
                        onKey(TermKey.BACKSPACE)
                    }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""

            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

            override fun getSelectedText(flags: Int): CharSequence? = null

            override fun getEditable(): Editable? {
                val ed = super.getEditable()
                ed?.clear()
                return ed
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!isCurrentConnection()) return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            composingBuffer = ""
                            onKey(TermKey.ENTER)
                            resetImeState()
                        }
                        KeyEvent.KEYCODE_DEL -> {
                            if (composingBuffer.isNotEmpty()) {
                                composingBuffer = composingBuffer.dropLast(1)
                            }
                            onKey(TermKey.BACKSPACE)
                        }
                        KeyEvent.KEYCODE_FORWARD_DEL -> {
                            composingBuffer = ""
                            onKey(TermKey.DEL)
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            composingBuffer = ""
                            onKey(TermKey.UP)
                            resetImeState()
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            composingBuffer = ""
                            onKey(TermKey.DOWN)
                            resetImeState()
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            composingBuffer = ""
                            onKey(TermKey.LEFT)
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            composingBuffer = ""
                            onKey(TermKey.RIGHT)
                        }
                        KeyEvent.KEYCODE_MOVE_HOME -> {
                            composingBuffer = ""
                            onKey(TermKey.HOME)
                        }
                        KeyEvent.KEYCODE_MOVE_END -> {
                            composingBuffer = ""
                            onKey(TermKey.END)
                        }
                        KeyEvent.KEYCODE_PAGE_UP -> {
                            composingBuffer = ""
                            onKey(TermKey.PGUP)
                        }
                        KeyEvent.KEYCODE_PAGE_DOWN -> {
                            composingBuffer = ""
                            onKey(TermKey.PGDN)
                        }
                        KeyEvent.KEYCODE_TAB -> {
                            composingBuffer = ""
                            onKey(TermKey.TAB)
                            resetImeState()
                        }
                        KeyEvent.KEYCODE_ESCAPE -> {
                            composingBuffer = ""
                            onKey(TermKey.ESC)
                            resetImeState()
                        }
                        else -> {
                            if (event.isCtrlPressed && event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                                composingBuffer = ""
                                val ctrlByte = (event.keyCode - KeyEvent.KEYCODE_A + 1).toByte()
                                onSendBytes(byteArrayOf(ctrlByte))
                                resetImeState()
                            } else {
                                val unicodeChar = event.unicodeChar
                                if (unicodeChar == '\n'.code || unicodeChar == '\r'.code) {
                                    composingBuffer = ""
                                    onKey(TermKey.ENTER)
                                    resetImeState()
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
