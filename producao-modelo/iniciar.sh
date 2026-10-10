#!/usr/bin/env bash
# Inicia a Fruteira em PRODUÇÃO (sem Maven/compilação): PostgreSQL + Keycloak (Docker), 5 serviços Java (jar) e o front.
#   USE_NGINX=true  -> HTTPS com nginx (rode antes ./ssl.sh letsencrypt)
#   USE_NGINX=false -> front por servidor-web.mjs em http://localhost:5173 (sem HTTPS)
set -u
cd "$(dirname "$0")"
mkdir -p logs; PIDS=()
[ -f .env ] || { echo "❌ .env não encontrado"; exit 1; }
set -a; . <(tr -d '\r' < .env); set +a
USE_NGINX=${USE_NGINX:-false}
need=(java docker); [ "$USE_NGINX" = true ] || need+=(node)
for c in "${need[@]}"; do command -v $c >/dev/null || { echo "❌ '$c' não encontrado no PATH"; exit 1; }; done
for v in DB_PASS KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASS; do
  case "${!v:-}" in ""|troque*) echo "❌ Defina um valor seguro para $v no .env"; exit 1;; esac
done
PERFIL=()
if [ "$USE_NGINX" = true ]; then
  case "${FRONT_DOMAIN:-}${KEYCLOAK_DOMAIN:-}" in ""|*exemplo*) echo "❌ Troque FRONT_DOMAIN/KEYCLOAK_DOMAIN pelos domínios reais no .env"; exit 1;; esac
  [ -f nginx/certs/live/fruteira/fullchain.pem ] || { echo "❌ Sem certificado TLS. Rode ./ssl.sh letsencrypt (ou ./ssl.sh local)"; exit 1; }
  PERFIL=(--profile nginx)
fi
aguardar() { for i in $(seq 1 90); do (echo > /dev/tcp/127.0.0.1/$2) >/dev/null 2>&1 && { echo "✔ $1 no ar (porta $2)"; return; }; sleep 2; done; echo "⚠ $1 não respondeu em 3 min — veja logs/$1.log"; }
cleanup() { echo; echo "Encerrando serviços Java..."; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; wait 2>/dev/null; exit 0; }
trap cleanup INT TERM

echo "Subindo PostgreSQL e Keycloak${USE_NGINX:+...}"
docker compose -f docker-compose.prod.yml ${PERFIL[@]+"${PERFIL[@]}"} up -d || exit 1
aguardar keycloak 8180
for s in catalogo:8081 estoque:8082 vendas:8083 retaguarda:8084 tef-agent:8090; do
  n=${s%%:*}; p=${s##*:}
  [ -f "servicos/$n/quarkus-run.jar" ] || { echo "❌ servicos/$n não encontrado (rode gerar-producao de novo)"; exit 1; }
  echo "Iniciando $n..."
  (cd "servicos/$n" && exec java ${JAVA_OPTS:--Xmx384m} -jar quarkus-run.jar) > "logs/$n.log" 2>&1 &
  PIDS+=($!); aguardar "$n" "$p"
done
if [ "$USE_NGINX" != true ]; then
  node servidor-web.mjs 5173 > logs/web.log 2>&1 &
  PIDS+=($!); aguardar web 5173
fi
echo; echo "🍎 Fruteira no ar: ${FRONT_URL:-http://localhost:5173}"
echo "🔐 Keycloak: ${KEYCLOAK_URL:-http://localhost:8180}  (gerente/supervisor/operador, senha temporária 'trocar123' — troque no primeiro acesso)"
echo "Logs em ./logs — Ctrl+C encerra os serviços Java (os contêineres seguem rodando; ./parar.sh para tudo)"
wait
