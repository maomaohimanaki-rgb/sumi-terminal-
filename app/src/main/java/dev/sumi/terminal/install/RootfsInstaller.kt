package dev.sumi.terminal.install

import android.content.Context
import android.system.Os
import dev.sumi.terminal.data.repository.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class RootfsInstaller(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository
) {
    companion object {
        const val ARCH_ROOTFS_URL = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
        const val FALLBACK_ARCH_URL = "https://mirrors.dotsrc.org/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz"
        private const val MAX_RETRIES = 4
        private val ESSENTIAL_BINARIES = setOf(
            "bin", "sbin", "usr/bin", "usr/sbin", "usr/lib", "etc"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(
        InstallState(
            phase = if (isInstalled()) InstallPhase.READY else InstallPhase.IDLE,
            progress = if (isInstalled()) 1f else 0f,
            isInstalling = false,
            isComplete = isInstalled()
        )
    )
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val partArchiveFile: File
        get() = File(context.cacheDir, "ArchLinuxARM-aarch64-latest.tar.gz.part")

    fun isInstalled(): Boolean {
        val rootfs = workspaceRepository.rootfsDir
        val bashFile = File(rootfs, "usr/bin/bash")
        return workspaceRepository.installedMarkerFile.exists() && bashFile.exists()
    }

    suspend fun startInstall(forceReinstall: Boolean = false) = withContext(Dispatchers.IO) {
        if (_state.value.isInstalling) return@withContext

        if (!forceReinstall && isInstalled()) {
            applyArchPostConfiguration(workspaceRepository.rootfsDir) {}
            _state.value = _state.value.copy(
                phase = InstallPhase.READY,
                progress = 1f,
                isInstalling = false,
                isComplete = true,
                error = null
            )
            return@withContext
        }

        val logs = mutableListOf<String>()
        fun appendLog(msg: String) {
            logs.add(msg)
            _state.value = _state.value.copy(logLines = logs.takeLast(12))
        }

        try {
            _state.value = _state.value.copy(
                phase = InstallPhase.CHECKING,
                progress = 0.02f,
                isInstalling = true,
                isComplete = false,
                error = null
            )
            appendLog("Verificando espelhos oficiais Arch Linux ARM64...")

            val archiveTarget = partArchiveFile
            downloadWithRangeResume(
                urls = listOf(ARCH_ROOTFS_URL, FALLBACK_ARCH_URL),
                destFile = archiveTarget,
                onLog = ::appendLog
            )

            _state.value = _state.value.copy(
                phase = InstallPhase.EXTRACTING,
                progress = 0.55f
            )
            appendLog("Extraindo árvore rootfs com symlinks POSIX nativos...")

            extractRootfsArchive(
                archiveFile = archiveTarget,
                rootfsDir = workspaceRepository.rootfsDir,
                onLog = ::appendLog
            )

            _state.value = _state.value.copy(
                phase = InstallPhase.CONFIGURING,
                progress = 0.94f
            )
            appendLog("Configurando DNS, /etc/pacman.conf (DisableSandbox) e perfil root...")

            applyArchPostConfiguration(
                rootfsDir = workspaceRepository.rootfsDir,
                onLog = ::appendLog
            )

            workspaceRepository.installedMarkerFile.writeText("arch-arm64-ready\n", Charsets.UTF_8)
            runCatching { archiveTarget.delete() }

            appendLog("Arch Linux ARM64 pronto para execução via PRoot.")
            _state.value = _state.value.copy(
                phase = InstallPhase.READY,
                progress = 1f,
                isInstalling = false,
                isComplete = true,
                error = null
            )
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            appendLog("Erro: $msg")
            _state.value = _state.value.copy(
                phase = InstallPhase.ERROR,
                isInstalling = false,
                isComplete = false,
                error = msg
            )
        }
    }

    private suspend fun downloadWithRangeResume(
        urls: List<String>,
        destFile: File,
        onLog: (String) -> Unit
    ) {
        var lastException: Throwable? = null

        for (attempt in 1..MAX_RETRIES) {
            val activeUrl = urls[(attempt - 1) % urls.size]
            try {
                val existingBytes = if (destFile.exists()) destFile.length() else 0L
                val reqBuilder = Request.Builder()
                    .url(activeUrl)
                    .header("User-Agent", "SumiTerminal/2.0 (Android; AArch64)")

                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                    onLog("Retomando download a partir de ${existingBytes / (1024 * 1024)} MB...")
                } else {
                    onLog("Conectando a $activeUrl (tentativa $attempt/$MAX_RETRIES)...")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (code == 416) {
                        onLog("Arquivo .tar.gz já completo em cache.")
                        return
                    }
                    if (!response.isSuccessful && code != 206) {
                        throw IOException("HTTP $code ao baixar rootfs")
                    }

                    val isPartial = (code == 206)
                    val contentLen = response.body?.contentLength() ?: -1L
                    val totalExpected = if (isPartial && contentLen > 0) {
                        existingBytes + contentLen
                    } else if (contentLen > 0) {
                        contentLen
                    } else {
                        550L * 1024L * 1024L
                    }

                    val startOffset = if (isPartial) existingBytes else 0L
                    val bodyStream = response.body?.byteStream()
                        ?: throw IOException("Stream HTTP vazio")

                    FileOutputStream(destFile, isPartial).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = startOffset
                        var windowBytes = 0L
                        var windowStartMs = System.currentTimeMillis()

                        while (true) {
                            val n = bodyStream.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            windowBytes += n

                            val now = System.currentTimeMillis()
                            val elapsed = now - windowStartMs
                            if (elapsed >= 250L) {
                                val speed = (windowBytes * 1000L) / elapsed.coerceAtLeast(1L)
                                val ratio = (downloaded.toFloat() / totalExpected.coerceAtLeast(1L).toFloat())
                                    .coerceIn(0f, 1f)
                                _state.value = _state.value.copy(
                                    phase = InstallPhase.DOWNLOADING,
                                    progress = 0.05f + (ratio * 0.48f),
                                    downloadedBytes = downloaded,
                                    totalBytes = totalExpected,
                                    speedBytesPerSec = speed
                                )
                                windowBytes = 0L
                                windowStartMs = now
                            }
                        }
                        out.flush()
                    }
                }
                return
            } catch (e: Throwable) {
                lastException = e
                onLog("Oscilação de rede (${e.message}). Retentando com backoff...")
                if (attempt < MAX_RETRIES) {
                    delay(1500L * attempt)
                }
            }
        }

        throw lastException ?: IOException("Falha ao baixar Arch Linux ARM64 após $MAX_RETRIES tentativas")
    }

    private fun extractRootfsArchive(
        archiveFile: File,
        rootfsDir: File,
        onLog: (String) -> Unit
    ) {
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        val totalArchiveBytes = archiveFile.length().coerceAtLeast(1L)
        var bytesReadCompressed = 0L
        var entriesCount = 0
        val deferredHardLinks = mutableListOf<Pair<File, File>>()

        val countingStream = object : FilterInputStream(BufferedInputStream(FileInputStream(archiveFile), 128 * 1024)) {
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) bytesReadCompressed++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) bytesReadCompressed += n
                return n
            }
        }

        GzipCompressorInputStream(countingStream).use { gzIn ->
            TarArchiveInputStream(gzIn).use { tarIn ->
                val copyBuf = ByteArray(64 * 1024)
                while (true) {
                    val entry = tarIn.nextTarEntry ?: break
                    val cleanName = entry.name
                        .removePrefix("./")
                        .removePrefix("/")
                        .trim()
                    if (cleanName.isEmpty() || cleanName.startsWith("dev/") || cleanName.startsWith("proc/") || cleanName.startsWith("sys/")) {
                        continue
                    }

                    val targetFile = File(rootfsDir, cleanName)
                    val canonicalRoot = rootfsDir.canonicalPath
                    val parentCanonical = targetFile.parentFile?.canonicalPath ?: canonicalRoot
                    if (!parentCanonical.startsWith(canonicalRoot)) {
                        continue
                    }

                    targetFile.parentFile?.let { parent ->
                        if (!parent.exists()) parent.mkdirs()
                    }

                    when {
                        entry.isDirectory -> {
                            if (!targetFile.exists()) targetFile.mkdirs()
                            runCatching { Os.chmod(targetFile.path, 493) } // 0755
                        }
                        entry.isSymbolicLink -> {
                            deleteExistingOrDangling(targetFile)
                            val linkTarget = entry.linkName
                            val linked = runCatching {
                                Os.symlink(linkTarget, targetFile.path)
                                true
                            }.getOrDefault(false)
                            if (!linked) {
                                deferredHardLinks.add(File(targetFile.parentFile, linkTarget) to targetFile)
                            }
                        }
                        entry.isLink -> {
                            deleteExistingOrDangling(targetFile)
                            val cleanLink = entry.linkName.removePrefix("./").removePrefix("/")
                            val linkSource = File(rootfsDir, cleanLink)
                            val linked = runCatching {
                                Os.link(linkSource.path, targetFile.path)
                                true
                            }.getOrDefault(false)
                            if (!linked) {
                                deferredHardLinks.add(linkSource to targetFile)
                            }
                        }
                        entry.isFile -> {
                            deleteExistingOrDangling(targetFile)
                            FileOutputStream(targetFile).use { fos ->
                                var remaining = entry.size
                                while (remaining > 0L) {
                                    val toRead = minOf(remaining, copyBuf.size.toLong()).toInt()
                                    val r = tarIn.read(copyBuf, 0, toRead)
                                    if (r <= 0) break
                                    fos.write(copyBuf, 0, r)
                                    remaining -= r
                                }
                            }
                            val mode = entry.mode and 511 // 0777
                            val isExecPath = ESSENTIAL_BINARIES.any { cleanName.startsWith("$it/") } ||
                                    cleanName.endsWith(".so") ||
                                    cleanName.contains(".so.")
                            val finalMode = if (isExecPath || (mode and 73) != 0) {
                                493 // 0755
                            } else {
                                if (mode > 0) mode else 420 // 0644
                            }
                            runCatching { Os.chmod(targetFile.path, finalMode) }
                        }
                    }

                    entriesCount++
                    if (entriesCount % 250 == 0) {
                        val ratio = (bytesReadCompressed.toFloat() / totalArchiveBytes.toFloat()).coerceIn(0f, 1f)
                        _state.value = _state.value.copy(
                            phase = InstallPhase.EXTRACTING,
                            progress = 0.55f + (ratio * 0.38f),
                            extractedEntries = entriesCount,
                            currentEntry = cleanName
                        )
                    }
                }
            }
        }

        // Resolve any deferred hardlinks where target appeared later in the archive
        for ((src, dst) in deferredHardLinks) {
            if (src.exists() && !dst.exists()) {
                runCatching {
                    src.copyTo(dst, overwrite = true)
                    Os.chmod(dst.path, 493)
                }
            }
        }
        onLog("Extração concluída: $entriesCount arquivos e links simbólicos.")
    }

    private fun deleteExistingOrDangling(file: File) {
        if (isSymlinkPresent(file) || file.exists()) {
            runCatching { Os.remove(file.path) }
            runCatching { file.delete() }
        }
    }

    private fun isSymlinkPresent(file: File): Boolean {
        return try {
            val st = Os.lstat(file.path)
            (st.st_mode and 0xF000) == 0xA000
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyArchPostConfiguration(rootfsDir: File, onLog: (String) -> Unit) {
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        val varLibPacman = File(rootfsDir, "var/lib/pacman")
        File(varLibPacman, "db.lck").takeIf { it.exists() }?.delete()

        val resolvConf = File(etcDir, "resolv.conf")
        deleteExistingOrDangling(resolvConf)
        resolvConf.writeText(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n",
            Charsets.UTF_8
        )
        runCatching { Os.chmod(resolvConf.path, 420) }

        val hosts = File(etcDir, "hosts")
        if (!hosts.exists() || hosts.length() == 0L) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n", Charsets.UTF_8)
            runCatching { Os.chmod(hosts.path, 420) }
        }

        // Configure /etc/pacman.conf for PRoot (DisableSandbox, #CheckSpace, #SandboxUser, SigLevel = Never)
        val pacmanConf = File(etcDir, "pacman.conf")
        if (pacmanConf.exists()) {
            var content = pacmanConf.readText(Charsets.UTF_8)
            content = content
                .replace(Regex("(?m)^\\s*CheckSpace\\b"), "#CheckSpace")
                .replace(Regex("(?m)^\\s*SandboxUser\\b.*"), "#SandboxUser = alpm")
                .replace(Regex("(?m)^\\s*#\\s*DisableSandbox\\b.*"), "DisableSandbox")
                .replace(Regex("(?m)^\\s*#?\\s*SigLevel\\b.*"), "SigLevel = Never")
                .replace(Regex("(?m)^\\s*#?\\s*LocalFileSigLevel\\b.*"), "LocalFileSigLevel = Optional")
            if (!Regex("(?m)^\\s*DisableSandbox\\b").containsMatchIn(content)) {
                content = if (content.contains("[options]")) {
                    content.replaceFirst("[options]", "[options]\nDisableSandbox\nSigLevel = Never\nLocalFileSigLevel = Optional")
                } else {
                    "[options]\nDisableSandbox\nSigLevel = Never\nLocalFileSigLevel = Optional\n$content"
                }
            } else if (!Regex("(?m)^\\s*SigLevel\\s*=\\s*Never\\b").containsMatchIn(content)) {
                content = content.replaceFirst("DisableSandbox", "DisableSandbox\nSigLevel = Never\nLocalFileSigLevel = Optional")
            }
            pacmanConf.writeText(content, Charsets.UTF_8)
            runCatching { Os.chmod(pacmanConf.path, 420) }
        }

        // Initialize /etc/pacman.d/gnupg with 0700 permissions
        val gnupgDir = File(etcDir, "pacman.d/gnupg")
        if (!gnupgDir.exists()) {
            gnupgDir.mkdirs()
        }
        runCatching { Os.chmod(gnupgDir.path, 448) } // 0700 = 448 in decimal

        val mirrorlist = File(etcDir, "pacman.d/mirrorlist")
        mirrorlist.parentFile?.mkdirs()
        mirrorlist.writeText(
            "Server = http://mirror.archlinuxarm.org/\$arch/\$repo\n" +
                    "Server = https://mirrors.dotsrc.org/archlinuxarm/\$arch/\$repo\n",
            Charsets.UTF_8
        )
        runCatching { Os.chmod(mirrorlist.path, 420) }

        // Mask mkinitcpio to avoid pointless initramfs image generation in PRoot
        val mkinitcpioBin = File(rootfsDir, "usr/bin/mkinitcpio")
        if (mkinitcpioBin.exists()) {
            runCatching { Os.chmod(mkinitcpioBin.path, 420) } // remove exec permission (0644)
        }
        val hooksDir = File(etcDir, "pacman.d/hooks").apply { mkdirs() }
        File(hooksDir, "90-mkinitcpio-install.hook").writeText(
            """
            [Trigger]
            Type = Path
            Operation = Install
            Operation = Upgrade
            Target = usr/lib/modules/*/vmlinuz
            Target = usr/lib/initcpio/*

            [Action]
            Description = Masked mkinitcpio hook (PRoot Android environment)
            When = PostTransaction
            Exec = /usr/bin/true
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )

        val rootHome = File(rootfsDir, "root").apply { mkdirs() }
        runCatching { Os.chmod(rootHome.path, 493) }
        File(rootHome, ".hushlogin").writeText("", Charsets.UTF_8)

        File(rootHome, ".bash_profile").writeText(
            """
            export HOME=/root
            export USER=root
            export TERM=xterm-256color
            export COLORTERM=truecolor
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin
            export PS1='root@arch:\w# '
            if [ -f ~/.bashrc ]; then
                . ~/.bashrc
            fi
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
        onLog("Configuração pós-extração do Arch Linux ARM64 aplicada.")
    }
}
