package dev.sumi.terminal.install

enum class InstallPhase(val title: String, val detail: String) {
    IDLE("Aguardando", "Pronto para instalar o Arch Linux ARM64 Rootfs oficial."),
    CHECKING("Verificando Ambiente", "Inspecionando partição ext4 interna e binários PRoot..."),
    DOWNLOADING("Baixando Arch Linux ARM64", "Download HTTP Range resumível do espelho oficial archlinuxarm.org..."),
    EXTRACTING("Extraindo Rootfs & Symlinks", "Descompactando tar.gz com symlinks nativos Os.symlink e Os.chmod..."),
    CONFIGURING("Pós-Configuração Arch Linux", "Configurando resolv.conf, locale.conf, pacman.conf (DisableSandbox) e .bash_profile..."),
    READY("Arch Linux ARM64 Pronto", "Rootfs instalado e verificado (.sumi-installed).")
}
