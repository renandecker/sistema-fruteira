#!/usr/bin/env bash
# Atalho: o backup completo fica na pasta ./backup (veja backup/LEIA-ME.txt)
cd "$(dirname "$0")"; exec ./backup/backup.sh "$@"
