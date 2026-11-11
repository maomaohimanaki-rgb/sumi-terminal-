package dev.sumi.terminal.files

import android.system.Os
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClipboardMode {
    COPY, MOVE
}

data class FileClipboard(
    val sources: List<File> = emptyList(),
    val mode: ClipboardMode = ClipboardMode.COPY
) {
    val isEmpty: Boolean get() = sources.isEmpty()
    val count: Int get() = sources.size
}

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isSymlink: Boolean = FileOps.isSymlink(file),
    val symlinkTarget: String? = if (isSymlink) FileOps.readSymlinkTarget(file) else null,
    val isDirectory: Boolean = file.isDirectory,
    val sizeBytes: Long = if (file.isDirectory) 0L else file.length(),
    val lastModified: Long = file.lastModified(),
    val isExecutable: Boolean = file.canExecute(),
    val extension: String = file.extension.lowercase(Locale.ROOT)
) {
    val absolutePath: String get() = file.absolutePath

    fun formattedSize(): String {
        if (isSymlink && symlinkTarget != null) {
            return "→ $symlinkTarget"
        }
        if (isDirectory) {
            val count = file.listFiles()?.size ?: 0
            return "$count itens"
        }
        return when {
            sizeBytes < 1024 -> "${sizeBytes} B"
            sizeBytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024f)
            else -> String.format(Locale.US, "%.2f MB", sizeBytes / (1024f * 1024f))
        }
    }

    fun formattedDate(): String {
        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        return sdf.format(Date(lastModified))
    }

    fun permissionsString(): String {
        val t = when {
            isSymlink -> 'l'
            isDirectory -> 'd'
            else -> '-'
        }
        val r = if (file.canRead()) 'r' else '-'
        val w = if (file.canWrite()) 'w' else '-'
        val x = if (file.canExecute() || isDirectory) 'x' else '-'
        return "$t$r$w${x}r-xr-x"
    }
}
