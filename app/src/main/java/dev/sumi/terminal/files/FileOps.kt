package dev.sumi.terminal.files

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object FileOps {

    fun isSymlink(file: File): Boolean {
        return runCatching {
            val stat = Os.lstat(file.absolutePath)
            OsConstants.S_ISLNK(stat.st_mode)
        }.getOrDefault(false)
    }

    fun readSymlinkTarget(file: File): String? {
        return runCatching {
            Os.readlink(file.absolutePath)
        }.getOrNull()
    }

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
                    runCatching { Os.chmod(target.path, 420) }
                }
                target
            }
        }

    suspend fun createNewDirectory(parent: File, name: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(parent, name.trim())
                target.mkdirs()
                runCatching { Os.chmod(target.path, 493) }
                target
            }
        }

    /**
     * Cross-filesystem recursive copy supporting both FUSE (/storage/emulated/0) and ext4
     * internal Arch Linux rootfs (filesDir/usr/rootfs), preserving Unix symlinks and permissions.
     */
    suspend fun copyBatchCrossFilesystem(
        sources: List<File>,
        destinationDir: File
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }
            var copiedCount = 0
            for (src in sources) {
                val destTarget = resolveDestinationTarget(destinationDir, src)
                copyNodeRecursive(src, destTarget)
                copiedCount++
            }
            copiedCount
        }
    }

    /**
     * Cross-filesystem move handling EXDEV (Invalid cross-device link) between /storage and ext4.
     * Attempts atomic rename first; if across mount points, performs full stream copy + symlink
     * recreation followed by clean recursive deletion of the source.
     */
    suspend fun moveBatchCrossFilesystem(
        sources: List<File>,
        destinationDir: File
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }
            var movedCount = 0
            for (src in sources) {
                if (src.parentFile?.canonicalPath == destinationDir.canonicalPath) continue
                val destTarget = resolveDestinationTarget(destinationDir, src)

                // Try direct renameTo first (works if same filesystem)
                val renamed = if (!destTarget.exists()) {
                    runCatching { src.renameTo(destTarget) }.getOrDefault(false)
                } else {
                    false
                }

                if (!renamed) {
                    // Fallback for EXDEV cross-filesystem (/storage <-> /data/data/.../files/usr/rootfs)
                    copyNodeRecursive(src, destTarget)
                    deleteNodeSafe(src)
                }
                movedCount++
            }
            movedCount
        }
    }

    private fun resolveDestinationTarget(destinationDir: File, src: File): File {
        var candidate = File(destinationDir, src.name)
        if (candidate.absolutePath == src.absolutePath) {
            val base = src.nameWithoutExtension
            val ext = src.extension.let { if (it.isNotEmpty()) ".$it" else "" }
            candidate = File(destinationDir, "${base}_copy$ext")
        }
        return candidate
    }

    private fun copyNodeRecursive(src: File, dst: File) {
        // 1. Check if src is a symbolic link before checking isDirectory (do not dereference symlinks!)
        if (isSymlink(src)) {
            val linkTarget = readSymlinkTarget(src)
            if (linkTarget != null) {
                deleteNodeSafe(dst)
                dst.parentFile?.mkdirs()
                Os.symlink(linkTarget, dst.path)
                return
            }
        }

        // 2. Directory recursive copy
        if (src.isDirectory) {
            if (!dst.exists()) {
                dst.mkdirs()
            }
            runCatching { Os.chmod(dst.path, 493) } // 0755
            val children = src.listFiles().orEmpty()
            for (child in children) {
                copyNodeRecursive(child, File(dst, child.name))
            }
            return
        }

        // 3. Regular file stream copy (64KB buffer)
        if (src.exists()) {
            dst.parentFile?.mkdirs()
            deleteNodeSafe(dst)
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }
            val mode = if (src.canExecute()) 493 else 420 // 0755 or 0644
            runCatching { Os.chmod(dst.path, mode) }
        }
    }

    fun deleteNodeSafe(target: File): Boolean {
        if (isSymlink(target)) {
            return runCatching {
                Os.remove(target.path)
                true
            }.getOrDefault(false)
        }
        if (target.isDirectory) {
            target.listFiles()?.forEach { child ->
                deleteNodeSafe(child)
            }
        }
        return runCatching { target.delete() }.getOrDefault(false)
    }

    suspend fun deleteBatch(targets: List<File>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                var count = 0
                for (t in targets) {
                    if (deleteNodeSafe(t)) count++
                }
                count
            }
        }

    suspend fun rename(target: File, newName: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = File(target.parentFile, newName.trim())
                if (target.renameTo(dest)) dest else error("Falha ao renomear arquivo")
            }
        }
}
