package dev.sumi.terminal.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WorkspaceRepository(private val context: Context) {

    val rootfsDir: File
        get() = File(context.filesDir, "arch-arm64-rootfs").apply { mkdirs() }

    val homeDir: File
        get() = File(rootfsDir, "home/sumi").apply { mkdirs() }

    suspend fun ensureInitialWorkspace() = withContext(Dispatchers.IO) {
        val home = homeDir
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """
                # ~/.bashrc - Sumi Terminal (墨) Arch Linux ARM64
                export TERM=xterm-256color
                export COLORTERM=truecolor
                export LANG=en_US.UTF-8
                export EDITOR=zen
                export PS1='\[\e[38;2;230;201;138m\]sumi@arch\[\e[0m\]:\[\e[38;2;107;158;155m\]\w\[\e[0m\]$ '

                alias ll='ls -la'
                alias gs='git status'
                alias pac='pacman -Syu'
                """.trimIndent() + "\n"
            )
        }

        val projectsDir = File(home, "projects/sumi-core").apply { mkdirs() }
        val mainRs = File(projectsDir, "main.rs")
        if (!mainRs.exists()) {
            mainRs.writeText(
                """
                // Sumi Terminal (墨) - Low-Latency PTY Engine Demo
                use std::io::{self, Write};

                fn main() -> io::Result<()> {
                    let palette = ["Sumi1 #121212", "Shironeri #E6E6E6", "Kinari #E6C98A", "Kurenai #D9383A"];
                    println!("墨 Sumi Native Engine initialized.");
                    for token in palette.iter() {
                        writeln!(io::stdout(), "  • {}", token)?;
                    }
                    Ok(())
                }
                """.trimIndent() + "\n"
            )
        }

        val scriptSh = File(home, "scripts/deploy_node.sh").apply { parentFile?.mkdirs() }
        if (!scriptSh.exists()) {
            scriptSh.writeText(
                """
                #!/usr/bin/env bash
                set -euo pipefail

                echo "[*] Validating Arch Linux ARM64 PRoot mounts..."
                uname -a
                echo "[✓] Environment healthy. Ready for compilation."
                """.trimIndent() + "\n"
            )
            scriptSh.setExecutable(true)
        }

        val notesMd = File(home, "README_SUMI.md")
        if (!notesMd.exists()) {
            notesMd.writeText(
                """
                # 墨 Sumi Terminal (v2.0-zen)

                Filosofia **Wabi-Sabi**: minimalismo funcional, precisão tipográfica e zero latência.

                ## Atalhos e Recursos
                - **Word Magnet**: Arraste as alças de seleção no terminal para atrair magneticamente palavras inteiras.
                - **Safe Paste**: Colagens multilinha ou longas são interceptadas com Bracketed Paste (`\e[200~...\e[201~`).
                - **Zen Editor**: Clique em qualquer arquivo no gerenciador para abrir no editor dedicado com barra de símbolos.
                - **Comandos Rápidos**: Experimente `neofetch`, `pacman -Syu`, `git status`, `cargo run`, `ls -la`, `htop`.
                """.trimIndent() + "\n"
            )
        }
    }
}
