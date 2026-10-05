#!/usr/bin/env bash
# Aplica database/pre_cadastro_frutas_verduras.sql no PostgreSQL LOCAL (banco "fruteira").
# Usa o psql instalado ou, se não houver, o contêiner "postgres" do docker-compose.prod.yml. Rode antes o catalogo-service em produção (cria as tabelas).
set -e
cd "$(dirname "$0")/.."
[ -f .env ] && { set -a; . <(tr -d '\r' < .env); set +a; }
SQL=database/pre_cadastro_frutas_verduras.sql; USUARIO=${DB_USER:-postgres}
if command -v psql >/dev/null; then
  PGPASSWORD="${DB_PASS:-}" psql -h localhost -U "$USUARIO" -d fruteira -v ON_ERROR_STOP=1 -f "$SQL"
elif command -v docker >/dev/null && docker compose -f docker-compose.prod.yml ps -q postgres 2>/dev/null | grep -q .; then
  docker compose -f docker-compose.prod.yml exec -T postgres psql -U "$USUARIO" -d fruteira -v ON_ERROR_STOP=1 < "$SQL"
else
  echo "❌ Nem psql nem o contêiner postgres estão disponíveis. Sem PostgreSQL local? Em desenvolvimento o pré-cadastro já roda sozinho (H2)"
  echo "   e também pelo botão 'Importar frutas e verduras' na tela Produtos."; exit 1
fi
echo "✔ Pré-cadastro aplicado."
