package dev.sumi.terminal.install

enum class InstallPhase(val title: String, val detail: String) {
    CHECKING("Verificando Ambiente", "Inspecionando integridade do rootfs Arch Linux ARM64..."),
    EXTRACTING_PROOT("Configurando PRoot & Binários", "Vinculando loader aarch64 e descritores PTY..."),
    BOOTSTRAPPING_ARCH("Montando Arch Linux Rootfs", "Criando hierarquia FHS (/etc, /usr, /var/lib/pacman, /home/sumi)..."),
    CONFIGURING_SHELL("Sincronizando Shell & Keyring", "Aplicando .bashrc, locales UTF-8 e variáveis xterm-256color..."),
    READY("Ambiente Pronto", "Subsistema Sumi PTY operacional.")
}
