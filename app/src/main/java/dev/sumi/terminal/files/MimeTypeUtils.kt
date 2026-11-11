package dev.sumi.terminal.files

import java.io.File

object MimeTypeUtils {
    private val textExtensions = setOf(
        "kt", "kts", "java", "rs", "c", "cpp", "h", "hpp", "py", "sh", "bash", "zsh",
        "json", "toml", "yaml", "yml", "xml", "md", "txt", "conf", "ini", "gradle",
        "properties", "cmake", "dockerfile", "gitignore", "bashrc", "profile", "js", "ts", "css", "html"
    )

    fun isEditableText(file: File): Boolean {
        if (file.isDirectory) return false
        val name = file.name.lowercase()
        if (name.startsWith(".") || name in setOf("makefile", "dockerfile", "cmakelists.txt", "pkgbuild")) {
            return true
        }
        val ext = file.extension.lowercase()
        if (ext in textExtensions) return true
        return file.length() < 512 * 1024
    }

    fun badgeForFile(file: File): String {
        if (file.isDirectory) return "DIR"
        val name = file.name.lowercase()
        val ext = file.extension.lowercase()
        return when {
            name == ".bashrc" || ext == "sh" || ext == "bash" -> "SH"
            ext == "rs" -> "RS"
            ext == "kt" || ext == "kts" -> "KT"
            ext == "cpp" || ext == "c" || ext == "h" -> "C++"
            ext == "py" -> "PY"
            ext == "md" -> "MD"
            ext == "json" || ext == "toml" || ext == "yaml" -> "CFG"
            else -> "TXT"
        }
    }
}
