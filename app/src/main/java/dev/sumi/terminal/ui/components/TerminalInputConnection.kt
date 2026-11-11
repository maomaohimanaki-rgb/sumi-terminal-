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
 * 1. Configures `InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS`
 *    WITHOUT any Password variation or `IME_FLAG_NO_PERSONALIZED_LEARNING`, ensuring
 *    SwiftKey and Gboard open in Normal Mode (never Incognito/Anonymous) with their
 *    top toolbar and clipboard active.
 * 2. Preserves the active composing buffer (`composingBuffer`) while the IME has an
 *    open composition (`setComposingText`), computing exact character deltas via
 *    `computeImeDelta` so 1 keystroke emits strictly 1 character to the PTY.
 * 3. Explicitly finishes the composing region (`finishComposingText()`) and resets
 *    the IME state before clearing the buffer when Enter or a control key is pressed.
 */
class TerminalInputConnectionView(
    context: Context,
    var onSendBytes: (ByteArray) -> Unit = {},
    var onKey: (TermKey) -> Unit = {},
    var onRawText: (String) -> Unit = {},
    var onInterceptPaste: (String) -> Unit = {}
) : View(context) {

    private var composingBuffer: String = ""
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

    /**
     * Explicitly finishes any active IME composition before clearing `composingBuffer`
     * and restarting the input connection (e.g., on Enter, Ctrl+C, or Tab).
     */
    fun resetImeState() {
        runCatching {
            activeConnection?.finishComposingText()
            activeConnection?.editable?.clear()
        }
        composingBuffer = ""
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        composingBuffer = ""
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI
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
                    super.finishComposingText()
                    composingBuffer = ""
                    onKey(TermKey.ENTER)
                    resetImeState()
                } else if (isCommit) {
                    // Composition completed; clear composing tracker only after commit
                    super.finishComposingText()
                    composingBuffer = ""
                } else {
                    // Never clear composingBuffer while composition is active
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
                return super.setComposingRegion(start, end)
            }

            override fun finishComposingText(): Boolean {
                if (!isCurrentConnection()) return false
                composingBuffer = ""
                return super.finishComposingText()
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                if (!isCurrentConnection()) return false
                super.finishComposingText()
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

            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
                if (length <= 0 || composingBuffer.isEmpty()) return ""
                return composingBuffer.takeLast(length)
            }

            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

            override fun getSelectedText(flags: Int): CharSequence? = null

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
