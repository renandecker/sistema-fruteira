#!/usr/bin/env bash
# Compila o launcher (só JDK) em fruteira-launcher.jar. Uso: ./build.sh [pasta-de-saída]
set -e
cd "$(dirname "$0")"
OUT="${1:-build}"; mkdir -p "$OUT" && TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
javac -encoding UTF-8 --release 21 -d "$TMP" src/br/com/fruteira/launcher/*.java
printf 'Main-Class: br.com.fruteira.launcher.Main\n' > "$TMP/manifest.txt"
jar --create --file "$OUT/fruteira-launcher.jar" --manifest "$TMP/manifest.txt" -C "$TMP" .
echo "✔ $OUT/fruteira-launcher.jar"
