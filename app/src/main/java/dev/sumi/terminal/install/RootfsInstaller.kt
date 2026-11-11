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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

class RootfsInstaller(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository
) {
    companion object {
        const val ARCH_ROOTFS_URL = "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
        const val FALLBACK_ARCH_URL = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
        private const val MAX_RETRIES = 6
        private val ESSENTIAL_BINARIES = setOf(
            "usr/bin/bash",
            "usr/bin/sh",
            "usr/bin/pacman",
            "usr/bin/env",
            "usr/bin/su",
            "usr/bin/id",
            "usr/bin/ls",
            "usr/bin/cat",
            "usr/bin/chmod",
            "usr/bin/mkdir",
            "bin/bash",
            "bin/sh"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(
        if (isInstalled()) {
            InstallState(
                phase = InstallPhase.READY,
                progress = 1f,
                isComplete = true
            )
        } else {
            InstallState(phase = InstallPhase.IDLE)
        }
    )
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val partArchiveFile: File
        get() = File(context.filesDir, "rootfs.tar.gz.part")

    fun isInstalled(): Boolean {
        val marker = workspaceRepository.installedMarkerFile
        val bashBin = File(workspaceRepository.rootfsDir, "usr/bin/bash")
        return marker.exists() && (bashBin.exists() || isSymlinkPresent(bashBin))
    }

    private fun isSymlinkPresent(file: File): Boolean {
        return runCatching {
            Os.lstat(file.absolutePath)
            true
        }.getOrDefault(false)
    }

    suspend fun startInstall(forceClean: Boolean = false) = withContext(Dispatchers.IO) {
        if (_state.value.isInstalling) return@withContext
        if (isInstalled() && !forceClean) {
            runCatching {
                applyArchPostConfiguration(workspaceRepository.rootfsDir) {}
            }
            _state.value = InstallState(
                phase = InstallPhase.READY,
                progress = 1f,
                isComplete = true,
                logLines = listOf("[✓] Arch Linux ARM64 (.sumi-installed) verificado e configurado.")
            )
            return@withContext
        }

        val logs = mutableListOf<String>()
        fun appendLog(msg: String) {
            synchronized(logs) {
                logs.add(msg)
                if (logs.size > 80) logs.removeAt(0)
            }
            _state.value = _state.value.copy(logLines = logs.toList())
        }

        _state.value = InstallState(
            phase = InstallPhase.CHECKING,
            progress = 0.01f,
            isInstalling = true,
            error = null
        )

        runCatching {
            val rootfsDir = workspaceRepository.rootfsDir
            if (forceClean) {
                appendLog("[*] Limpando instalação anterior...")
                rootfsDir.deleteRecursively()
                rootfsDir.mkdirs()
                if (partArchiveFile.exists()) partArchiveFile.delete()
            }

            // 1. Download Resumível com HTTP Range e Exponential Backoff
            appendLog("[*] Iniciando download resumível: $ARCH_ROOTFS_URL")
            downloadWithRangeResume(
                urls = listOf(ARCH_ROOTFS_URL, FALLBACK_ARCH_URL),
                targetPartFile = partArchiveFile,
                onLog = ::appendLog
            )

            // 2. Extração Real com TarArchiveInputStream(GzipCompressorInputStream) + Os.symlink + Os.chmod
            appendLog("[*] Extraindo arquivo Tar/Gzip preservando symlinks e permissões Unix...")
            extractRootfsArchive(
                archiveFile = partArchiveFile,
                destRootfs = rootfsDir,
                onLog = ::appendLog
            )

            // 3. Pós-Configuração do Arch Linux ARM64
            _state.value = _state.value.copy(
                phase = InstallPhase.CONFIGURING,
                progress = 0.96f
            )
            appendLog("[*] Aplicando pós-configuração (/etc/resolv.conf, locale.conf, pacman.conf, .bash_profile)...")
            applyArchPostConfiguration(rootfsDir, ::appendLog)

            // Remover arquivo .part após extração validada para liberar armazenamento
            if (partArchiveFile.exists()) {
                partArchiveFile.delete()
            }

            // 4. Criar marcador definitivo .sumi-installed
            workspaceRepository.installedMarkerFile.writeText(
                "archlinux-arm64-proot-installed=${System.currentTimeMillis()}\n",
                Charsets.UTF_8
            )
            appendLog("[✓] Arch Linux ARM64 instalado com sucesso (.sumi-installed).")

            _state.value = _state.value.copy(
                phase = InstallPhase.READY,
                progress = 1f,
                isInstalling = false,
                isComplete = true,
                error = null
            )
        }.onFailure { err ->
            appendLog("[!] ERRO: ${err.message ?: err.javaClass.simpleName}")
            _state.value = _state.value.copy(
                isInstalling = false,
                error = err.message ?: "Falha durante a instalação do Arch Linux ARM64."
            )
        }
    }

    private suspend fun downloadWithRangeResume(
        urls: List<String>,
        targetPartFile: File,
        onLog: (String) -> Unit
    ) {
        var attempt = 0
        var urlIndex = 0
        while (attempt < MAX_RETRIES) {
            val currentUrl = urls[urlIndex % urls.size]
            val existingBytes = if (targetPartFile.exists()) targetPartFile.length() else 0L

            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "SumiTerminal/2.0 (ArchLinuxARM; aarch64)")
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
                onLog("[*] Retomando download a partir de ${existingBytes / (1024 * 1024)} MB (Range: bytes=$existingBytes-)...")
            }

            val result = runCatching {
                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (code == 416) {
                        // Range Not Satisfiable -> arquivo já foi completamente baixado
                        onLog("[✓] Arquivo parcial já está completo (${existingBytes / (1024 * 1024)} MB).")
                        return
                    }
                    if (!response.isSuccessful && code != 206) {
                        throw IllegalStateException("HTTP $code ao baixar $currentUrl")
                    }

                    val isResume = (code == 206 && existingBytes > 0L)
                    val contentLen = response.body?.contentLength() ?: -1L
                    val totalExpected = if (contentLen > 0) {
                        if (isResume) existingBytes + contentLen else contentLen
                    } else {
                        580L * 1024L * 1024L // Estimativa ~580MB caso chunked
                    }

                    val bodyStream = response.body?.byteStream()
                        ?: throw IllegalStateException("Corpo HTTP vazio")

                    FileOutputStream(targetPartFile, isResume).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var currentDownloaded = if (isResume) existingBytes else 0L
                        var windowBytes = 0L
                        var lastTickMs = System.currentTimeMillis()

                        while (true) {
                            val read = bodyStream.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            currentDownloaded += read
                            windowBytes += read

                            val now = System.currentTimeMillis()
                            val elapsedMs = now - lastTickMs
                            if (elapsedMs >= 250L) {
                                val speed = (windowBytes * 1000L) / elapsedMs.coerceAtLeast(1L)
                                val ratio = (currentDownloaded.toFloat() / totalExpected.coerceAtLeast(1L)).coerceIn(0f, 1f)
                                _state.value = _state.value.copy(
                                    phase = InstallPhase.DOWNLOADING,
                                    progress = ratio * 0.55f,
                                    downloadedBytes = currentDownloaded,
                                    totalBytes = totalExpected,
                                    speedBytesPerSec = speed
                                )
                                windowBytes = 0L
                                lastTickMs = now
                            }
                        }
                        out.flush()
                    }
                }
            }

            if (result.isSuccess) {
                onLog("[✓] Download concluído (${targetPartFile.length() / (1024 * 1024)} MB).")
                return
            } else {
                attempt++
                urlIndex++
                val errMsg = result.exceptionOrNull()?.message ?: "Erro de rede"
                if (attempt >= MAX_RETRIES) {
                    throw IllegalStateException("Falha no download após $MAX_RETRIES tentativas: $errMsg")
                }
                val backoffMs = min(16000L, 1000L * (1L shl (attempt - 1)))
                onLog("[!] Tentativa $attempt/$MAX_RETRIES falhou ($errMsg). Reconectando em ${backoffMs / 1000}s...")
                delay(backoffMs)
            }
        }
    }

    private fun extractRootfsArchive(
        archiveFile: File,
        destRootfs: File,
        onLog: (String) -> Unit
    ) {
        val totalArchiveBytes = archiveFile.length().coerceAtLeast(1L)
        var bytesReadCompressed = 0L
        var entriesCount = 0
        var lastUiUpdateMs = 0L

        val countingStream = object : FilterInputStream(BufferedInputStream(FileInputStream(archiveFile), 128 * 1024)) {
            override fun read(): Int {
                val b = super.read()
                if (b != -1) bytesReadCompressed++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) bytesReadCompressed += n
                return n
            }
        }

        // Deferred hardlinks and permissions to ensure targets exist first
        val deferredHardLinks = mutableListOf<Pair<File, File>>()

        TarArchiveInputStream(GzipCompressorInputStream(countingStream), "UTF-8").use { tarIn ->
            val copyBuf = ByteArray(64 * 1024)
            var entry: TarArchiveEntry? = tarIn.nextEntry
            while (entry != null) {
                val rawName = entry.name.removePrefix("./").removePrefix("/")
                if (rawName.isNotEmpty() && !rawName.startsWith("dev/") && rawName != "dev") {
                    val target = File(destRootfs, rawName)
                    val mode = entry.mode and 0xFFF

                    when {
                        entry.isDirectory -> {
                            target.mkdirs()
                            // Diretórios: mode or 0700 (448) para garantir escrita pelo app
                            runCatching { Os.chmod(target.path, if (mode != 0) (mode or 448) else 493) }
                        }
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            deleteExistingOrDangling(target)
                            runCatching {
                                Os.symlink(entry.linkName, target.path)
                            }
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTargetName = entry.linkName.removePrefix("./").removePrefix("/")
                            val linkSource = File(destRootfs, linkTargetName)
                            deferredHardLinks.add(linkSource to target)
                        }
                        entry.isFile -> {
                            target.parentFile?.let { parent ->
                                if (!parent.exists()) {
                                    parent.mkdirs()
                                    runCatching { Os.chmod(parent.path, 493) }
                                }
                            }
                            deleteExistingOrDangling(target)
                            FileOutputStream(target).use { out ->
                                var n = tarIn.read(copyBuf)
                                while (n != -1) {
                                    out.write(copyBuf, 0, n)
                                    n = tarIn.read(copyBuf)
                                }
                            }
                            val finalMode = when {
                                rawName in ESSENTIAL_BINARIES -> 493 // 0755 (0x1ED)
                                (mode and 73) != 0 -> (mode or 493)  // executável -> 0755
                                mode != 0 -> (mode or 384)           // arquivo normal -> mode or 0600
                                else -> 420                          // 0644
                            }
                            runCatching { Os.chmod(target.path, finalMode) }
                        }
                    }

                    entriesCount++
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs >= 200L) {
                        val extractRatio = (bytesReadCompressed.toFloat() / totalArchiveBytes).coerceIn(0f, 1f)
                        _state.value = _state.value.copy(
                            phase = InstallPhase.EXTRACTING,
                            progress = 0.55f + (extractRatio * 0.40f),
                            extractedEntries = entriesCount,
                            currentEntry = rawName
                        )
                        lastUiUpdateMs = now
                    }
                }
                entry = tarIn.nextEntry
            }
        }

        // Resolve hardlinks inside tar archive ( via Os.link or symlink/copy fallback )
        for ((src, dst) in deferredHardLinks) {
            if (src.exists()) {
                deleteExistingOrDangling(dst)
                val linked = runCatching {
                    Os.link(src.path, dst.path)
                    true
                }.getOrDefault(false)
                if (!linked) {
                    runCatching {
                        src.copyTo(dst, overwrite = true)
                        Os.chmod(dst.path, 493)
                    }
                }
            }
        }

        // Ensure essential binaries explicitly have 0755 (0x1ED)
        for (rel in ESSENTIAL_BINARIES) {
            val bin = File(destRootfs, rel)
            if (bin.exists()) {
                runCatching { Os.chmod(bin.path, 493) }
            }
        }

        onLog("[✓] Extraídos $entriesCount nós Unix (arquivos, diretórios e symlinks).")
    }

    private fun deleteExistingOrDangling(file: File) {
        runCatching {
            Os.lstat(file.path)
            Os.remove(file.path)
        }
        if (file.exists()) {
            file.delete()
        }
    }

    private fun applyArchPostConfiguration(rootfsDir: File, onLog: (String) -> Unit) {
        // 1. /etc/resolv.conf (remover symlink quebrado do systemd-resolved antes de gravar)
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        val resolvConf = File(etcDir, "resolv.conf")
        deleteExistingOrDangling(resolvConf)
        resolvConf.writeText(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
            Charsets.UTF_8
        )
        runCatching { Os.chmod(resolvConf.path, 420) } // 0644

        // 2. /etc/locale.conf
        val localeConf = File(etcDir, "locale.conf")
        deleteExistingOrDangling(localeConf)
        localeConf.writeText(
            "LANG=C.UTF-8\nLC_ALL=C.UTF-8\n",
            Charsets.UTF_8
        )
        runCatching { Os.chmod(localeConf.path, 420) }

        // 3. /etc/pacman.conf: Desativar CheckSpace (#CheckSpace), descomentar/injetar DisableSandbox e desativar SandboxUser em [options]
        val pacmanConf = File(etcDir, "pacman.conf")
        if (pacmanConf.exists()) {
            var content = pacmanConf.readText(Charsets.UTF_8)
            content = content.replace(Regex("(?m)^\\s*CheckSpace\\b"), "#CheckSpace")
            content = content.replace(Regex("(?m)^\\s*SandboxUser\\b.*"), "#SandboxUser = alpm")
            content = content.replace(Regex("(?m)^\\s*#\\s*DisableSandbox\\b.*"), "DisableSandbox")
            if (!Regex("(?m)^\\s*DisableSandbox\\b").containsMatchIn(content)) {
                content = if (content.contains("[options]")) {
                    content.replaceFirst("[options]", "[options]\nDisableSandbox")
                } else {
                    "[options]\nDisableSandbox\n$content"
                }
            }
            pacmanConf.writeText(content, Charsets.UTF_8)
            runCatching { Os.chmod(pacmanConf.path, 420) }
        } else {
            pacmanConf.writeText(
                """
                [options]
                HoldPkg     = pacman glibc
                Architecture = aarch64
                DisableSandbox
                #CheckSpace
                SigLevel    = Required DatabaseOptional
                LocalFileSigLevel = Optional

                [core]
                Include = /etc/pacman.d/mirrorlist

                [extra]
                Include = /etc/pacman.d/mirrorlist
                """.trimIndent() + "\n",
                Charsets.UTF_8
            )
            runCatching { Os.chmod(pacmanConf.path, 420) }
        }

        // 4. /root/.hushlogin, /root/.bash_profile & /root/.bashrc
        val rootHome = File(rootfsDir, "root").apply { mkdirs() }
        runCatching { Os.chmod(rootHome.path, 493) }

        val hushLogin = File(rootHome, ".hushlogin")
        if (!hushLogin.exists()) {
            hushLogin.writeText("", Charsets.UTF_8)
            runCatching { Os.chmod(hushLogin.path, 420) }
        }

        val bashProfile = File(rootHome, ".bash_profile")
        bashProfile.writeText(
            """
            # /root/.bash_profile - Sumi Terminal (墨) Arch Linux ARM64
            export HOME=/root
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
        runCatching { Os.chmod(bashProfile.path, 420) }

        val bashrc = File(rootHome, ".bashrc")
        bashrc.writeText(
            """
            export PS1='root@arch:\w# '
            alias ls='ls --color=auto'
            alias ll='ls -la --color=auto'
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
        runCatching { Os.chmod(bashrc.path, 420) }

        // Ensure /tmp exists with 0777 inside rootfs
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }
        runCatching { Os.chmod(tmpDir.path, 511) }

        onLog("[✓] Pós-configuração concluída (/etc/resolv.conf, locale.conf, pacman.conf DisableSandbox, .hushlogin, .bash_profile).")
    }
}
