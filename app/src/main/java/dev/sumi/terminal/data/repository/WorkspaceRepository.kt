package dev.sumi.terminal.data.repository

import android.content.Context
import android.os.Environment
import java.io.File

class WorkspaceRepository(private val context: Context) {

    /**
     * Rootfs directory inside internal storage (`filesDir/usr/rootfs`).
     */
    val rootfsDir: File
        get() = File(context.filesDir, "usr/rootfs").apply { mkdirs() }

    /**
     * Root home directory inside Arch Linux rootfs (`filesDir/usr/rootfs/root`).
     */
    val rootHomeDir: File
        get() = File(rootfsDir, "root").apply { mkdirs() }

    /**
     * PRoot temporary directory (`cacheDir/proot-tmp`).
     */
    val prootTmpDir: File
        get() = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    /**
     * External Android shared storage (`/storage/emulated/0`).
     */
    val sdcardDir: File
        get() = Environment.getExternalStorageDirectory()

    /**
     * Android Downloads directory (`/storage/emulated/0/Download`).
     */
    val downloadsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    val installedMarkerFile: File
        get() = File(rootfsDir, ".sumi-installed")
}
