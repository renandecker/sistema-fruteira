#!/usr/bin/env bash
# Backup da Fruteira:  ./backup.sh [--destino /media/pendrive/fruteira] [--senha XXX]   (config em backup.env)
export FRUTEIRA_CWD="$PWD"; cd "$(dirname "$0")"
command -v node >/dev/null || { echo "❌ Node.js 18+ não encontrado (https://nodejs.org)"; exit 1; }
exec node backup.mjs "$@"
