package dev.sumi.terminal.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileOps {
    suspend fun listDirectory(dir: File, showHidden: Boolean = true): List<FileItem> =
        withContext(Dispatchers.IO) {
            val children = dir.listFiles().orEmpty()
            children
                .filter { showHidden || !it.name.startsWith(".") }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .map { FileItem(it) }
        }

    suspend fun createNewFile(parent: File, name: String, initialContent: String = ""): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(parent, name.trim())
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    target.writeText(initialContent, Charsets.UTF_8)
                }
                target
            }
        }

    suspend fun createNewDirectory(parent: File, name: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(parent, name.trim())
                target.mkdirs()
                target
            }
        }

    suspend fun deleteRecursively(target: File): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching { target.deleteRecursively() }
        }

    suspend fun rename(target: File, newName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = File(target.parentFile, newName.trim())
                if (target.renameTo(dest)) dest else error("Falha ao renomear arquivo")
            }
        }
}
