#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_DIR="./keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/sumi-release.jks"
ALIAS="sumi-key"
PASS="sumiTerminal2026SecureKey"

mkdir -p "$KEYSTORE_DIR"

if [ -f "$KEYSTORE_FILE" ]; then
    echo "[!] KeyStore já existe em $KEYSTORE_FILE. Abortando criação para não sobrescrever chaves."
    exit 0
fi

echo "[*] Gerando KeyStore RSA 4096-bit com validade de 30 anos..."
keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10950 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -dname "CN=Sumi Terminal, OU=Dev, O=Sumi Project, L=Kyoto, ST=Kyoto, C=JP"

cat <<EOF > "$KEYSTORE_DIR/keystore.properties"
RELEASE_STORE_FILE=../keystore/sumi-release.jks
RELEASE_STORE_PASSWORD=$PASS
RELEASE_KEY_ALIAS=$ALIAS
RELEASE_KEY_PASSWORD=$PASS
EOF

echo "[✓] KeyStore criada com sucesso em: $KEYSTORE_FILE"
echo "[✓] Arquivo de propriedades configurado em: $KEYSTORE_DIR/keystore.properties"
