#!/usr/bin/env bash
# Confere as migrations do Flyway: aplica os 4 serviços num PostgreSQL de teste (PGlite, sem Docker) e compara com as @Entity.
# Na primeira vez baixa o PGlite (npm). Rode sempre que mexer numa @Entity ou numa migration.
set -e
command -v node >/dev/null || { echo "❌ Node.js 18+ não encontrado."; exit 1; }
cd "$(dirname "$0")/teste"
[ -d node_modules/@electric-sql/pglite ] || npm install --no-audit --no-fund --silent
node testar-migrations.mjs
