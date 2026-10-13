#!/usr/bin/env bash
# Agenda o backup todo dia às 02:00 no cron do usuário (rode uma vez).  Para remover: crontab -e
cd "$(dirname "$0")"; DIR="$(pwd)"
LINHA="0 2 * * * cd \"$DIR\" && ./backup.sh >> \"$DIR/backup.log\" 2>&1"
( crontab -l 2>/dev/null | grep -vF "$DIR/backup.log"; echo "$LINHA" ) | crontab - && echo "✔ Agendado: $LINHA"
