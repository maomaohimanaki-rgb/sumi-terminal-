package dev.sumi.terminal.terminal

object AnsiConstants {
    const val ESC = '\u001B'
    const val BEL = '\u0007'
    const val BS = '\u0008'
    const val TAB = '\u0009'
    const val LF = '\u000A'
    const val CR = '\u000D'
    const val DEL = '\u007F'

    const val BRACKETED_PASTE_START = "\u001B[200~"
    const val BRACKETED_PASTE_END = "\u001B[201~"

    val KNOWN_EXECUTABLES = setOf(
        "git", "pacman", "cargo", "rustc", "docker", "podman", "vim", "nvim", "nano", "zen",
        "ls", "ll", "cd", "cat", "grep", "find", "sed", "awk", "chmod", "chown", "mkdir",
        "touch", "rm", "cp", "mv", "tar", "curl", "wget", "ssh", "scp", "top", "htop",
        "neofetch", "uname", "pwd", "echo", "clear", "exit", "help", "python", "python3",
        "node", "npm", "gradle", "make", "cmake", "clang", "gcc", "proot", "bash", "sh"
    )

    val ERROR_KEYWORDS = listOf("error:", "failed:", "fatal:", "panic:")
}
