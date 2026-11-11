package dev.sumi.terminal.data.preferences

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object TerminalPreferencesSerializer {
    /**
     * Copies an external background image URI into the internal app sandbox so that
     * URI permission expiration or process death never breaks the custom background.
     */
    suspend fun copyBackgroundToSandbox(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bgDir = File(context.filesDir, "backgrounds").apply { mkdirs() }
            val destFile = File(bgDir, "custom_bg_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Remove older background files to save storage
            bgDir.listFiles()?.filter { it.absolutePath != destFile.absolutePath }?.forEach { it.delete() }
            destFile.absolutePath
        }.getOrNull()
    }
}
