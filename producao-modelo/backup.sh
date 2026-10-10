#!/usr/bin/env bash
# Backup do banco da fruteira e do Keycloak em ./backups (agende no cron, ex.: todo dia às 2h)
cd "$(dirname "$0")"; set -a; . <(tr -d '\r' < .env); set +a
mkdir -p backups; d=$(date +%Y%m%d-%H%M)
docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U "${DB_USER:-postgres}" fruteira | gzip > "backups/fruteira-$d.sql.gz" && echo "✔ backups/fruteira-$d.sql.gz"
docker compose -f docker-compose.prod.yml exec -T keycloak-db pg_dump -U keycloak keycloak | gzip > "backups/keycloak-$d.sql.gz" && echo "✔ backups/keycloak-$d.sql.gz"
find backups -name '*.sql.gz' -mtime +30 -delete      # guarda 30 dias
