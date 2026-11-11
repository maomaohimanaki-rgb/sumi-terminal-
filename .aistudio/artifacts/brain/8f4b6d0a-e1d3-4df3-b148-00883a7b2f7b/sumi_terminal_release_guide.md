# 墨 Sumi Terminal (`v2.0-zen`) — Arquitetura, KeyStore & Release

## 1. Visão Geral da Reconstrução Arquitetural

O **Sumi Terminal** foi reconstruído do zero seguindo a estética **Wabi-Sabi** (minimalismo funcional japonês, tinta nanquim `Sumi1`, papel `Shironeri`, algodão cru `Kinari`, carmesim `Kurenai` e índigo `Aizome`) e corrigindo todas as causas-raiz do código legado:

1. **IME Nativo (`TerminalInputConnectionView`) & Preservação da Barra do Gboard:**
   - Substitui o `BasicTextField` por uma `View` Android nativa com `BaseInputConnection` (`TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS` e `IME_ACTION_NONE | IME_FLAG_NO_FULLSCREEN | IME_FLAG_NO_EXTRACT_UI`), eliminando duplicação de texto, capitalização automática indevida e perda de sincronia de cursor.
2. **Modo Colagem Segura (`PasteConfirmDialog` + Bracketed Paste):**
   - Qualquer colagem com mais de **80 caracteres** ou contendo quebra de linha (`\n` / `\r`) é interceptada antes de chegar ao PTY, exibindo pré-visualização numerada com destaque sintático e opção de envio seguro envolto em `\e[200~ ... \e[201~`.
3. **Word Magnet (`Hysteresis Word Magnet` em `TerminalSelection.kt`):**
   - Ao selecionar texto no `TerminalCanvas`, o ponteiro possui atração magnética de **1.8 células** para o início ou fim das palavras delimitadas por operadores de shell, disparando feedback tátil (`HapticFeedbackType.TextHandleMove`).
4. **Debounce de `TIOCSWINSZ` e Wrap de Backspace (`0x08`/`0x7F`):**
   - Eventos de redimensionamento do canvas durante a animação do teclado passam por uma janela de estabilização de **120ms** em `TerminalSession`, evitando corrupção da matriz `COLUMNS`/`LINES` do Readline. O `TerminalBuffer` recua linhas soft-wrapped quando `curCol == 0` recebe `0x08`.
5. **Persistência Nativa (`Jetpack DataStore`):**
   - Paletas Wabi-Sabi (`Carvão Sumi`, `Sakura Yoru`, `Oceano Aizome`, `Âmbar Kitsune`, `Matcha Chashitsu`, `Monocromático Puro`), estilo de cursor respirante (`Bloco`, `Linha`, `Sublinhado`), brilho, tamanho da fonte (`JetBrains Mono`) e imagem de fundo copiada para o sandbox interno são persistidos em `TerminalPreferences`.
6. **Editor Zen Standalone (`ZenEditorScreen.kt`):**
   - Editor desacoplado do gerenciador de arquivos com gutter dinâmico de numeração de linhas, barra de símbolos de programação (`Tab`, `{`, `}`, `(`, `)`, `[`, `]`, `"`, `'`, `$`, `=`, `;`, `:`, `|`, `\`, `/`), indicador de modificação (`• modificado`), salvamento atômico via arquivo temporário e botão de execução direta no terminal.

---

## 2. Credenciais da KeyStore Gerada (`keystore/sumi-release.jks`)

A KeyStore RSA de **4096 bits** (validade de 30 anos / 10.950 dias) foi gerada no repositório em:

- **Arquivo JKS:** `keystore/sumi-release.jks`
- **Propriedades:** `keystore/keystore.properties`
- **Key Alias:** `sumi-key`
- **Store / Key Password:** `sumiTerminal2026SecureKey`
- **Distinguished Name (DN):** `CN=Sumi Terminal, OU=Dev, O=Sumi Project, L=Kyoto, ST=Kyoto, C=JP`

---

## 3. Como Baixar o APK / Exportar no AI Studio

- **Via Interface do AI Studio:** No menu de configurações (canto superior direito do Google AI Studio), selecione **Download APK / Export Project ZIP** para obter o pacote instalável assinado e todo o código-fonte (incluindo `keystore/sumi-release.jks`, `scripts/generate_keystore.sh`, `scripts/build_release.sh` e `app/src/main/cpp/pty_core.cpp`).
- **Via Linha de Comando Local:**
  ```bash
  bash ./scripts/generate_keystore.sh
  bash ./scripts/build_release.sh
  ```
