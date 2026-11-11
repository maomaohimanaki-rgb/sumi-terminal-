package dev.sumi.terminal.files

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClipboardMode {
    COPY,
    MOVE
}

data class FileClipboard(
    val sources: List<File> = emptyList(),
    val mode: ClipboardMode = ClipboardMode.COPY
) {
    val isEmpty: Boolean
        get() = sources.isEmpty()

    val count: Int
        get() = sources.size
}

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val extension: String = file.extension,
    val isExecutable: Boolean = file.canExecute(),
    val sizeBytes: Long = if (file.isFile) file.length() else 0L,
    val lastModified: Long = file.lastModified(),
    val isSymlink: Boolean = FileOps.isSymlink(file),
    val symlinkTarget: String? = if (FileOps.isSymlink(file)) FileOps.readSymlinkTarget(file) else null
) {
    val absolutePath: String
        get() = file.absolutePath

    fun permissionsString(): String {
        val typeChar = when {
            isSymlink -> 'l'
            isDirectory -> 'd'
            else -> '-'
        }
        val r = if (file.canRead()) 'r' else '-'
        val w = if (file.canWrite()) 'w' else '-'
        val x = if (isExecutable || isDirectory) 'x' else '-'
        return "$typeChar$r$w$x"
    }

    fun formattedSize(): String {
        if (isDirectory) return "DIR"
        return when {
            sizeBytes < 1024L -> "${sizeBytes} B"
            sizeBytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024f)
            sizeBytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024f * 1024f))
            else -> String.format(Locale.US, "%.2f GB", sizeBytes / (1024f * 1024f * 1024f))
        }
    }

    fun formattedDate(): String {
        if (lastModified <= 0L) return ""
        val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        return sdf.format(Date(lastModified))
    }
}
