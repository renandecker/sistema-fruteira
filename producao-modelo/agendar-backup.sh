#!/usr/bin/env bash
# Agenda o backup diário às 02:00 (cron). Para remover: crontab -e e apague a linha "fruteira backup".
cd "$(dirname "$0")"; DIR="$(pwd)"
( crontab -l 2>/dev/null | grep -v "fruteira backup"; echo "0 2 * * * cd \"$DIR\" && ./backup.sh >> backups/backup.log 2>&1 # fruteira backup" ) | crontab -
echo "✔ Backup diário agendado para 02:00 (log em backups/backup.log)"
