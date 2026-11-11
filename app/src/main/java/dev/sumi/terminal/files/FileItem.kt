package dev.sumi.terminal.files

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val sizeBytes: Long = if (file.isDirectory) 0L else file.length(),
    val lastModified: Long = file.lastModified(),
    val isExecutable: Boolean = file.canExecute(),
    val extension: String = file.extension.lowercase(Locale.ROOT)
) {
    fun formattedSize(): String {
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
        val d = if (isDirectory) 'd' else '-'
        val r = if (file.canRead()) 'r' else '-'
        val w = if (file.canWrite()) 'w' else '-'
        val x = if (file.canExecute() || isDirectory) 'x' else '-'
        return "$d$r$w${x}r-xr-x"
    }
}
