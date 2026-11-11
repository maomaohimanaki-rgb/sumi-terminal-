# 墨 Sumi Terminal — v2.0-zen (Release Assinada)

## 📦 Como Baixar o APK Assinado

O APK compilado e assinado com certificado `sumi-release.jks` (`v1 + v2 + v3`) está disponível no explorador de arquivos à esquerda:

- **`/DOWNLOAD_AQUI/sumi-terminal-v2.0-zen-release.apk`**
- **`/sumi-terminal-v2.0-zen-release.apk`**

---

## 🔍 Diagnóstico da Causa Raiz e Correções Aplicadas

### 1. Por que o prompt `root@arch:~#` havia sumido (Tela Preta)?
- **Causa Raiz 1 (Pipes vs PTY Real):** O binário nativo de PTY não estava empacotado em `jniLibs/arm64-v8a/`, fazendo o `PtyNative` cair no fallback `ProcessBuilder` (que usa **pipes** anônimos onde `isatty(0) == 0`, e não um pseudo-terminal `/dev/pts/N`).
- **Causa Raiz 2 (`bash --login` sem `-i` sobre Pipe):** Quando a flag `-i` foi removida de `/usr/bin/bash --login` nas duas builds anteriores, o GNU `bash` verificou `isatty(0) == 0` (pipe) e entrou em **modo batch não-interativo**, que por especificação POSIX emite **0 bytes** (`b''`), nunca imprime `PS1` (`root@arch:~#`) e nunca ecoa caracteres digitados.
- **Solução Definitiva:**
  - Empacotada a biblioteca C nativa de pseudo-terminal **`libtermux.so`** (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) junto à ponte JNI `com.termux.terminal.JNI`, que abre `/dev/ptmx`, executa `grantpt()`, `unlockpt()`, `ptsname_r()`, cria nova sessão com `setsid()`, acopla `/dev/pts/N` como terminal controlador (`isatty(0) == 1`) e configura `IUTF8` e `TIOCSWINSZ`.
  - Mantidos os argumentos separados `"/usr/bin/bash"`, `"--login"`, `"-i"` para garantir modo interativo com prompt `root@arch:~# ` imediato em qualquer cenário.

### 2. Execução Limpa do `pacman -Syy` / `pacman -Syu`
- **`/etc/pacman.conf`:** Descomenta automaticamente `#DisableSandbox` -> `DisableSandbox` sob `[options]` e comenta `CheckSpace` e `SandboxUser`.
- **`/sys/fs/selinux` e `PROOT_L2S_DIR`:** Monta um diretório vazio sobre `/sys/fs/selinux` (`-b .../sys_empty:/sys/fs/selinux`) e define `PROOT_L2S_DIR` + `-L` no PRoot para evitar que o SELinux do Android host ou links físicos bloqueiem o `pacman`.
- **DNS e Mirrorlist:** Garante `/etc/resolv.conf` físico (`1.1.1.1` / `8.8.8.8`) e `/etc/pacman.d/mirrorlist` válido antes de subir a sessão.
