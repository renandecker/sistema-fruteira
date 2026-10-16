#!/usr/bin/env bash
# ./database/nova-migration.sh <catalogo|estoque|vendas|retaguarda> "descrição curta"
command -v node >/dev/null || { echo "❌ Node.js 18+ não encontrado."; exit 1; }
exec node "$(dirname "$0")/nova-migration.mjs" "$@"
