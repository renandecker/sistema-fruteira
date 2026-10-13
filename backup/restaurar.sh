#!/usr/bin/env bash
# Restaura:  ./restaurar.sh saida/fruteira-AAAAMMDD-HHMMSS-prod.tar.gz.enc [--senha XXX]
export FRUTEIRA_CWD="$PWD"; cd "$(dirname "$0")"
command -v node >/dev/null || { echo "❌ Node.js 18+ não encontrado"; exit 1; }
[ $# -ge 1 ] || { echo "Uso: ./restaurar.sh <arquivo> [--senha XXX] [--sim] [--forcar]"; exit 1; }
exec node restaurar.mjs "$@"
