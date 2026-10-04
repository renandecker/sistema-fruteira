#!/usr/bin/env bash
# Inicia a Fruteira conforme APP_ENV do arquivo .env
#   desenvolvimento: 4 serviços Quarkus em modo dev (H2, sem login nas APIs) + Vite
#   producao:        nginx HTTPS + Keycloak + PostgreSQL (Docker), serviços compilados (jar) com OIDC e front buildado (dist)
set -u
cd "$(dirname "$0")"
mkdir -p logs; PIDS=()

[ -f .env ] || { echo "❌ Arquivo .env não encontrado (copie de .env.example)"; exit 1; }
set -a; . <(tr -d '\r' < .env); set +a
APP_ENV=${APP_ENV:-desenvolvimento}
case "$APP_ENV" in desenvolvimento|producao) ;; *) echo "❌ APP_ENV inválido: '$APP_ENV' (use desenvolvimento ou producao)"; exit 1;; esac
echo "▶ Ambiente: $APP_ENV"

need=(java mvn node npm); [ "$APP_ENV" = producao ] && need+=(docker)
for cmd in "${need[@]}"; do command -v $cmd >/dev/null || { echo "❌ '$cmd' não encontrado no PATH"; exit 1; }; done

cleanup() { echo; echo "Encerrando serviços..."; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; wait 2>/dev/null; exit 0; }
trap cleanup INT TERM

aguardar() { # $1=nome $2=porta
  for i in $(seq 1 120); do
    (echo > /dev/tcp/127.0.0.1/$2) >/dev/null 2>&1 && { echo "✔ $1 no ar (porta $2)"; return; }
    sleep 2
  done
  echo "⚠ $1 não respondeu em 4 min — veja logs/$1.log"
}

if [ "$APP_ENV" = producao ]; then
  for v in DB_PASS KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASS; do
    case "${!v:-}" in ""|troque*) echo "❌ Defina um valor seguro para $v no .env"; exit 1;; esac
  done
  for v in FRONT_DOMAIN KEYCLOAK_DOMAIN KEYCLOAK_URL FRONT_URL; do [ -n "${!v:-}" ] || { echo "❌ Defina $v no .env"; exit 1; }; done
  case "$FRONT_DOMAIN$KEYCLOAK_DOMAIN" in *exemplo*) echo "❌ Troque FRONT_DOMAIN/KEYCLOAK_DOMAIN pelos domínios reais no .env"; exit 1;; esac
  [ "${KEYCLOAK_URL%/}" = "https://$KEYCLOAK_DOMAIN" ] || { echo "❌ KEYCLOAK_URL deve ser https://$KEYCLOAK_DOMAIN"; exit 1; }
  [ "${FRONT_URL%/}" = "https://$FRONT_DOMAIN" ] || { echo "❌ FRONT_URL deve ser https://$FRONT_DOMAIN"; exit 1; }
  [ -f nginx/certs/live/fruteira/fullchain.pem ] || { echo "❌ Sem certificado TLS. Rode ./ssl.sh letsencrypt (ou ./ssl.sh local para testes)"; exit 1; }
  echo "Gerando o front (dist)..."
  (cd pdv-web && { [ -d node_modules ] || npm install; } && npm run build) > logs/pdv-web.log 2>&1 || { echo "❌ Build do front falhou (logs/pdv-web.log)"; exit 1; }
  echo "Subindo nginx (HTTPS), Keycloak e PostgreSQL..."
  docker compose -f docker-compose.prod.yml up -d || exit 1
  aguardar keycloak 8180
fi

for s in catalogo-service:8081 estoque-service:8082 vendas-service:8083 retaguarda-service:8084; do
  nome=${s%%:*}; porta=${s##*:}
  echo "Iniciando $nome..."
  if [ "$APP_ENV" = producao ]; then
    (cd "$nome" && mvn -q package -DskipTests && exec java -jar target/quarkus-app/quarkus-run.jar) > "logs/$nome.log" 2>&1 &
  else
    (cd "$nome" && exec mvn -q quarkus:dev -Dquarkus.console.enabled=false -Ddebug=false) > "logs/$nome.log" 2>&1 &
  fi
  PIDS+=($!)
  aguardar "$nome" "$porta"
done

if [ "$APP_ENV" = producao ]; then
  aguardar nginx 443
else
  echo "Iniciando pdv-web..."
  (cd pdv-web && { [ -d node_modules ] || npm install; } && exec npm run dev -- --host) > logs/pdv-web.log 2>&1 &
  PIDS+=($!)
  aguardar pdv-web 5173
fi

echo
[ "$APP_ENV" = producao ] && echo "🍎 Tudo no ar (producao): https://$FRONT_DOMAIN" || echo "🍎 Tudo no ar (desenvolvimento): http://localhost:5173"
[ "$APP_ENV" = producao ] && echo "🔐 Keycloak: $KEYCLOAK_URL  (usuários iniciais: gerente/supervisor/operador, senha temporária 'trocar123')" \
                          || echo "🔑 Login local: gerente/gerente, supervisor/supervisor, operador/operador"
echo "Logs em ./logs  —  Ctrl+C para parar"
wait
