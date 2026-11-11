package dev.sumi.terminal.terminal

enum class TermKey(val label: String) {
    ESC("ESC"),
    TAB("TAB"),
    ENTER("⏎"),
    BACKSPACE("⌫"),
    UP("↑"),
    DOWN("↓"),
    LEFT("←"),
    RIGHT("→"),
    HOME("HOME"),
    END("END"),
    PGUP("PGUP"),
    PGDN("PGDN"),
    DEL("DEL"),
    CTRL_C("^C"),
    CTRL_D("^D"),
    CTRL_L("^L"),
    CTRL_Z("^Z")
}

data class Mods(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false
) {
    val isAnyActive: Boolean get() = ctrl || alt || shift
}
