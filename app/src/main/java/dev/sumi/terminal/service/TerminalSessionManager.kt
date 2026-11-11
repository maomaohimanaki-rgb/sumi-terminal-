package dev.sumi.terminal.service

import android.content.Context
import android.system.Os
import dev.sumi.terminal.data.repository.WorkspaceRepository
import dev.sumi.terminal.perf.PerformanceManager
import dev.sumi.terminal.ui.theme.SumiPalettes
import dev.sumi.terminal.ui.theme.WabiSabiPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionManager(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val performanceManager: PerformanceManager
) {
    private val tabCounter = AtomicInteger(0)

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    @Volatile
    var currentPalette: WabiSabiPalette = SumiPalettes.Carvao

    /**
     * Ensures that `libtalloc.so.2` and `libandroid-shmem.so` exist in `filesDir/usr/lib`
     * (via symlink or file copy from `nativeLibraryDir/libtalloc.so`) because Android's APK
     * packager strips `.so.2` suffixes while `libproot.so` requires `DT_NEEDED: libtalloc.so.2`.
     */
    private fun prepareProotRuntimeLibraries(nativeLibDir: File): File {
        val usrLibDir = File(context.filesDir, "usr/lib").apply { mkdirs() }
        runCatching { Os.chmod(usrLibDir.path, 493) } // 0755

        val tallocApkSo = File(nativeLibDir, "libtalloc.so")
        val tallocVersioned = File(usrLibDir, "libtalloc.so.2")
        if (tallocApkSo.exists()) {
            ensureLibraryAlias(tallocApkSo, tallocVersioned)
            ensureLibraryAlias(tallocApkSo, File(usrLibDir, "libtalloc.so"))
        }

        val shmemApkSo = File(nativeLibDir, "libandroid-shmem.so")
        if (shmemApkSo.exists()) {
            ensureLibraryAlias(shmemApkSo, File(usrLibDir, "libandroid-shmem.so"))
        }

        return usrLibDir
    }

    private fun ensureLibraryAlias(sourceSo: File, aliasFile: File) {
        runCatching {
            Os.lstat(aliasFile.path)
            Os.remove(aliasFile.path)
        }
        if (aliasFile.exists()) {
            aliasFile.delete()
        }
        val symlinked = runCatching {
            Os.symlink(sourceSo.absolutePath, aliasFile.absolutePath)
            true
        }.getOrDefault(false)

        if (!symlinked) {
            runCatching {
                sourceSo.copyTo(aliasFile, overwrite = true)
                Os.chmod(aliasFile.path, 493) // 0755
            }
        }
    }

    /**
     * Enforces `/etc/pacman.conf` (`DisableSandbox` under `[options]`, `#CheckSpace`, `#SandboxUser`),
     * `/etc/resolv.conf`, `/etc/hosts`, removes stale `db.lck`, and ensures `/root/.bash_profile`
     * and `/root/.bashrc` set `PS1='root@arch:\w# '` before launching PRoot.
     */
    private fun ensureRootfsPacmanAndHushLogin(rootfsDir: File) {
        runCatching {
            val etcDir = File(rootfsDir, "etc").apply { mkdirs() }

            // Ensure /etc/resolv.conf is a real file (not a dangling symlink to /run/systemd/resolve)
            val resolvConf = File(etcDir, "resolv.conf")
            runCatching {
                val st = Os.lstat(resolvConf.path)
                if ((st.st_mode and 0xF000) == 0xA000) {
                    Os.remove(resolvConf.path)
                }
            }
            if (!resolvConf.exists() || resolvConf.length() == 0L) {
                resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", Charsets.UTF_8)
                runCatching { Os.chmod(resolvConf.path, 420) }
            }

            // Ensure /etc/hosts exists
            val hostsFile = File(etcDir, "hosts")
            if (!hostsFile.exists() || hostsFile.length() == 0L) {
                hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n", Charsets.UTF_8)
                runCatching { Os.chmod(hostsFile.path, 420) }
            }

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

            // Enforce DisableSandbox, #CheckSpace, #SandboxUser, SigLevel = Never in /etc/pacman.conf
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

            // Ensure /etc/pacman.d/gnupg directory exists with 0700 permissions
            val gnupgDir = File(etcDir, "pacman.d/gnupg")
            if (!gnupgDir.exists()) {
                gnupgDir.mkdirs()
            }
            runCatching { Os.chmod(gnupgDir.path, 448) } // 0700 = 448 in decimal

            // Ensure /etc/pacman.d/mirrorlist has an active mirror
            val mirrorlist = File(etcDir, "pacman.d/mirrorlist")
            if (!mirrorlist.exists() || !mirrorlist.readText(Charsets.UTF_8).contains(Regex("(?m)^\\s*Server\\s*="))) {
                mirrorlist.parentFile?.mkdirs()
                mirrorlist.writeText(
                    "Server = http://mirror.archlinuxarm.org/\$arch/\$repo\n" +
                        "Server = http://fl.us.mirror.archlinuxarm.org/\$arch/\$repo\n",
                    Charsets.UTF_8
                )
                runCatching { Os.chmod(mirrorlist.path, 420) }
            }
        }
    }

    /**
     * Builds the native PRoot LaunchConfig targeting the extracted
     * Arch Linux ARM64 rootfs (`filesDir/usr/rootfs`) and `libproot.so` in `nativeLibraryDir`.
     */
    fun buildProotLaunchConfig(): LaunchConfig {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val usrLibDir = prepareProotRuntimeLibraries(nativeLibDir)

        val prootBin = File(nativeLibDir, "libproot.so")
        val prootLoader = File(nativeLibDir, "libproot-loader.so")
        val prootLoader32 = File(nativeLibDir, "libproot-loader32.so")

        val rootfsDir = workspaceRepository.rootfsDir
        val prootTmpDir = workspaceRepository.prootTmpDir
        runCatching { Os.chmod(prootTmpDir.path, 511) } // 0777

        val sysEmptyDir = File(context.filesDir, "usr/sys_empty").apply { mkdirs() }
        val l2sDir = File(rootfsDir, ".l2s").apply { mkdirs() }

        val archBash = File(rootfsDir, "usr/bin/bash")
        val isRootfsReady = workspaceRepository.installedMarkerFile.exists() && archBash.exists()

        return if (prootBin.exists() && isRootfsReady) {
            ensureRootfsPacmanAndHushLogin(rootfsDir)

            val args = arrayOf(
                "proot",
                "--kill-on-exit",
                "--link2symlink",
                "-L",
                "-0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${sysEmptyDir.absolutePath}:/sys/fs/selinux",
                "-b", "/storage/emulated/0:/sdcard",
                "-w", "/root",
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "USER=root",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "PS1=root@arch:\\w# ",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/bin:/sbin",
                "/usr/bin/bash",
                "--login",
                "-i"
            )
            val ldLibraryPath = "${usrLibDir.absolutePath}:${nativeLibDir.absolutePath}"
            val env = buildList {
                add("LD_LIBRARY_PATH=$ldLibraryPath")
                add("PROOT_TMP_DIR=${prootTmpDir.absolutePath}")
                add("PROOT_L2S_DIR=${l2sDir.absolutePath}")
                if (prootLoader.exists()) {
                    add("PROOT_LOADER=${prootLoader.absolutePath}")
                }
                if (prootLoader32.exists()) {
                    add("PROOT_LOADER_32=${prootLoader32.absolutePath}")
                }
                add("PROOT_NO_SECCOMP=1")
                add("HOME=/root")
                add("USER=root")
                add("TERM=xterm-256color")
                add("COLORTERM=truecolor")
                add("PS1=root@arch:\\w# ")
                add("LANG=C.UTF-8")
                add("LC_ALL=C.UTF-8")
            }.toTypedArray()

            LaunchConfig(
                cmd = prootBin.absolutePath,
                args = args,
                env = env,
                cwd = workspaceRepository.rootHomeDir.absolutePath
            )
        } else {
            LaunchConfig(
                cmd = "/system/bin/sh",
                args = arrayOf("sh", "-i"),
                env = arrayOf(
                    "HOME=${workspaceRepository.rootHomeDir.absolutePath}",
                    "TERM=xterm-256color",
                    "COLORTERM=truecolor",
                    "LANG=C.UTF-8",
                    "PATH=/system/bin:/system/xbin"
                ),
                cwd = workspaceRepository.rootHomeDir.absolutePath
            )
        }
    }

    fun ensureDefaultSession(): TerminalSession {
        val current = _sessions.value
        if (current.isNotEmpty()) {
            val active = current.firstOrNull { it.id == _activeSessionId.value } ?: current.first()
            _activeSessionId.value = active.id
            return active
        }
        return createNewSession()
    }

    fun createNewSession(customTitle: String? = null): TerminalSession {
        val num = tabCounter.incrementAndGet()
        val id = UUID.randomUUID().toString()
        val title = customTitle ?: "arch:$num"
        val session = TerminalSession(
            id = id,
            index = num,
            initialTitle = title,
            launchConfig = buildProotLaunchConfig(),
            performanceManager = performanceManager,
            paletteProvider = { currentPalette }
        )
        session.start()
        _sessions.value = _sessions.value + session
        _activeSessionId.value = session.id
        return session
    }

    fun restartAllSessionsWithProot() {
        val old = _sessions.value
        old.forEach { it.close() }
        _sessions.value = emptyList()
        tabCounter.set(0)
        createNewSession()
    }

    fun selectSession(id: String) {
        if (_sessions.value.any { it.id == id }) {
            _activeSessionId.value = id
        }
    }

    fun closeSession(id: String) {
        val list = _sessions.value
        val target = list.firstOrNull { it.id == id } ?: return
        target.close()
        val remaining = list.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            _sessions.value = emptyList()
            createNewSession()
        } else {
            _sessions.value = remaining
            if (_activeSessionId.value == id) {
                _activeSessionId.value = remaining.last().id
            }
        }
    }

    fun getActiveSession(): TerminalSession? {
        val id = _activeSessionId.value
        return _sessions.value.firstOrNull { it.id == id } ?: _sessions.value.firstOrNull()
    }
}
