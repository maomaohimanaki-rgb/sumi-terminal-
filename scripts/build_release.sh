#!/usr/bin/env bash
set -euo pipefail

echo "[1/4] Verificando KeyStore..."
if [ ! -f "./keystore/sumi-release.jks" ]; then
    bash ./scripts/generate_keystore.sh
fi

echo "[2/4] Compilando APK Release e Debug..."
if command -v gradle >/dev/null 2>&1; then
    gradle assembleDebug assembleRelease --no-daemon --stacktrace
else
    ./gradlew assembleDebug assembleRelease --no-daemon --stacktrace
fi

APK_PATH="./app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK_PATH" ]; then
    echo "[3/4] APK Release compilado com sucesso em: $APK_PATH"
    echo "===================================================="
    echo "  APK RELEASE GERADO COM SUCESSO!"
    echo "  Localização: $APK_PATH"
    echo "===================================================="
else
    echo "[-] Erro: APK não encontrado em $APK_PATH"
    exit 1
fi
