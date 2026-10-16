#!/usr/bin/env bash
# Inicia a Fruteira conforme o .env
#   APP_ENV=desenvolvimento : 4 serviços Quarkus em modo dev (H2, sem Docker) + Vite
#   APP_ENV=producao        : PostgreSQL + Keycloak (Docker), serviços compilados (jar) com OIDC e front buildado
#     USE_NGINX=false -> front via "vite preview" em http://localhost:5173 (teste local, sem HTTPS)
#     USE_NGINX=true  -> HTTPS com nginx nos domínios do .env (ver ssl.sh)
set -u
cd "$(dirname "$0")"
mkdir -p logs data; PIDS=()

[ -f .env ] || { echo "❌ Arquivo .env não encontrado (copie de .env.example)"; exit 1; }
set -a; . <(tr -d '\r' < .env); set +a
APP_ENV=${APP_ENV:-desenvolvimento}; USE_NGINX=${USE_NGINX:-false}
case "$APP_ENV" in desenvolvimento|producao) ;; *) echo "❌ APP_ENV inválido: '$APP_ENV' (use desenvolvimento ou producao)"; exit 1;; esac
case "$USE_NGINX" in true|false) ;; *) echo "❌ USE_NGINX inválido: '$USE_NGINX' (use true ou false)"; exit 1;; esac
echo "▶ Ambiente: $APP_ENV$([ "$APP_ENV" = producao ] && echo " (nginx/HTTPS: $USE_NGINX)")"

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

PERFIL=()
if [ "$APP_ENV" = producao ]; then
  for v in DB_PASS KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASS; do
    case "${!v:-}" in ""|troque*) echo "❌ Defina um valor seguro para $v no .env"; exit 1;; esac
  done
  for v in KEYCLOAK_URL FRONT_URL; do [ -n "${!v:-}" ] || { echo "❌ Defina $v no .env"; exit 1; }; done
  if [ "$USE_NGINX" = true ]; then
    for v in FRONT_DOMAIN KEYCLOAK_DOMAIN; do [ -n "${!v:-}" ] || { echo "❌ Defina $v no .env"; exit 1; }; done
    case "$FRONT_DOMAIN$KEYCLOAK_DOMAIN" in *exemplo*) echo "❌ Troque FRONT_DOMAIN/KEYCLOAK_DOMAIN pelos domínios reais no .env"; exit 1;; esac
    [ "${KEYCLOAK_URL%/}" = "https://$KEYCLOAK_DOMAIN" ] || { echo "❌ KEYCLOAK_URL deve ser https://$KEYCLOAK_DOMAIN"; exit 1; }
    [ "${FRONT_URL%/}" = "https://$FRONT_DOMAIN" ] || { echo "❌ FRONT_URL deve ser https://$FRONT_DOMAIN"; exit 1; }
    [ -f nginx/certs/live/fruteira/fullchain.pem ] || { echo "❌ Sem certificado TLS. Rode ./ssl.sh letsencrypt (ou ./ssl.sh local para testes)"; exit 1; }
    PERFIL=(--profile nginx)
  else
    echo "ℹ Produção local SEM HTTPS (USE_NGINX=false). Para HTTPS real: USE_NGINX=true no .env."
  fi
  [ "${TEF_PROVIDER:-simulador}" = simulador ] && echo "ℹ TEF em modo SIMULADOR (TEF_PROVIDER=simulador): não cobra cartão de verdade."
  [ -z "${TEF_TOKEN:-}" ] && echo "⚠ TEF_TOKEN vazio: defina um token no .env e em Configurações do caixa (qualquer página aberta no navegador pode chamar o agente local)."
  [ "${FISCAL_PROVIDER:-simulador}" = simulador ] && echo "ℹ NFC-e em modo SIMULADOR (FISCAL_PROVIDER=simulador): sem valor fiscal. Para a Nota Fiscal Gaúcha real: FISCAL_PROVIDER=sefaz (ver README)."
  if [ "${FISCAL_PROVIDER:-simulador}" = sefaz ]; then
    falta=""; for v in FISCAL_CNPJ FISCAL_RAZAO FISCAL_IE FISCAL_CMUN FISCAL_CSC_ID FISCAL_CSC FISCAL_CERT_PATH; do [ -z "${!v:-}" ] && falta="$falta $v"; done
    [ -n "$falta" ] && { echo "❌ FISCAL_PROVIDER=sefaz exige no .env:$falta"; exit 1; }
    [ -r "$FISCAL_CERT_PATH" ] || { echo "❌ Certificado A1 não encontrado/legível: $FISCAL_CERT_PATH"; exit 1; }
    [ "${FISCAL_AMBIENTE:-2}" = 1 ] && echo "⚠ NFC-e em PRODUÇÃO (FISCAL_AMBIENTE=1): as notas têm valor fiscal." || echo "ℹ NFC-e em HOMOLOGAÇÃO (FISCAL_AMBIENTE=2): sem valor fiscal."
  fi
  PIXP=$(echo "${PIX_PROVIDER:-simulador}" | tr 'a-z -' 'A-Z__')
  case "$PIXP" in
    SIMULADOR|EFI|MERCADO_PAGO|ASAAS|ITAU|BRADESCO|SICOOB|SICREDI) ;;
    *) echo "❌ PIX_PROVIDER inválido ('${PIX_PROVIDER}'). Use: SIMULADOR, EFI, MERCADO_PAGO, ASAAS, ITAU, BRADESCO, SICOOB ou SICREDI"; exit 1 ;;
  esac
  [ "$PIXP" = SIMULADOR ] && echo "ℹ Pix em modo SIMULADOR (PIX_PROVIDER=simulador): não é Pix real."
  if [ "$PIXP" != SIMULADOR ]; then
    case "$PIXP" in
      MERCADO_PAGO) req="PIX_MERCADO_PAGO_ACCESS_TOKEN PIX_MERCADO_PAGO_EMAIL_PAGADOR FRUTEIRA_PIX_WEBHOOK_URL" ;;
      ASAAS)        req="PIX_ASAAS_API_KEY PIX_ASAAS_CLIENTE_ID" ;;
      *)            req="PIX_${PIXP}_CLIENT_ID PIX_${PIXP}_CLIENT_SECRET PIX_${PIXP}_CERTIFICADO" ;;
    esac
    falta=""; for v in $req; do [ -z "${!v:-}" ] && falta="$falta $v"; done
    case "$PIXP" in MERCADO_PAGO|ASAAS) ;; *) k="PIX_${PIXP}_CHAVE"; [ -z "${PIX_CHAVE:-}" ] && [ -z "${!k:-}" ] && falta="$falta PIX_CHAVE" ;; esac
    [ -n "$falta" ] && { echo "❌ PIX_PROVIDER=$PIXP exige no .env:$falta"; exit 1; }
    c="PIX_${PIXP}_CERTIFICADO"; [ -n "${!c:-}" ] && [ ! -r "${!c}" ] && { echo "❌ Certificado do Pix não encontrado/legível: ${!c}"; exit 1; }
  fi
  [ "$PIXP" != SIMULADOR ] && [ -z "${FRUTEIRA_PIX_WEBHOOK_SEGREDO:-}" ] && { echo "❌ Pix com provedor real exige FRUTEIRA_PIX_WEBHOOK_SEGREDO no .env (o webhook é público)"; exit 1; }
  echo "Gerando o front (dist)..."
  (cd pdv-web && { [ -d node_modules ] || npm install; } && npm run build) > logs/pdv-web.log 2>&1 || { echo "❌ Build do front falhou (logs/pdv-web.log)"; exit 1; }
  echo "Subindo PostgreSQL e Keycloak$([ "$USE_NGINX" = true ] && echo ' e nginx')..."
  docker compose -f docker-compose.prod.yml ${PERFIL[@]+"${PERFIL[@]}"} up -d || exit 1
  aguardar keycloak 8180
fi

# Portas do sistema: 4 serviços, agente TEF e front. Processo antigo (execução anterior que não foi encerrada) nelas faz o serviço novo
# falhar com "porta em uso" e o front responder 500. Antes de subir, quem estiver ESCUTANDO nelas é encerrado.
# (PostgreSQL/Keycloak/nginx são do Docker e não entram aqui.) Para desligar: LIBERAR_PORTAS=false no .env.
PORTAS_APP=(8081 8082 8083 8084 8090 5173)
pids_na_porta() { # PIDs que escutam na porta $1
  if command -v lsof >/dev/null 2>&1; then lsof -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null
  elif command -v fuser >/dev/null 2>&1; then fuser "$1"/tcp 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$'
  elif command -v ss >/dev/null 2>&1; then ss -ltnp "sport = :$1" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2
  elif command -v netstat >/dev/null 2>&1; then netstat -ano 2>/dev/null | tr -d '\r' | awk -v p=":$1" '$1=="TCP" && $2 ~ p"$" && $4=="LISTENING" {print $5}'   # Git Bash (Windows)
  fi | sort -u
}
matar_pid() { # $1=pid: pede para encerrar e, se não sair em 5 s, força
  local pid=$1 i
  if command -v taskkill >/dev/null 2>&1 && ! command -v lsof >/dev/null 2>&1; then taskkill //PID "$pid" //T //F >/dev/null 2>&1; return; fi
  kill "$pid" 2>/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || return 0; sleep 0.5; done
  kill -9 "$pid" 2>/dev/null; sleep 0.5
}
liberar_portas() {
  [ "${LIBERAR_PORTAS:-true}" = false ] && { echo "ℹ LIBERAR_PORTAS=false: portas ocupadas não serão liberadas."; return; }
  local p pid pai nome ocupadas=0
  for p in "${PORTAS_APP[@]}"; do
    for pid in $(pids_na_porta "$p"); do
      [ "$pid" = "$$" ] && continue
      nome=$(ps -o args= -p "$pid" 2>/dev/null | cut -c1-70); [ -n "$nome" ] || nome=$(ps -o comm= -p "$pid" 2>/dev/null)
      echo "⚠ Porta $p em uso pelo processo $pid (${nome:-?}): encerrando..."
      pai=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
      matar_pid "$pid"
      # o "mvn quarkus:dev"/"npm run dev" que gerou o processo também sai (senão ele pode subir de novo)
      case "$(ps -o comm= -p "${pai:-0}" 2>/dev/null)" in mvn|java|npm|node|npx) [ "$pai" != "$$" ] && matar_pid "$pai";; esac
    done
  done
  for p in "${PORTAS_APP[@]}"; do
    [ -n "$(pids_na_porta "$p")" ] && { echo "❌ Não consegui liberar a porta $p (processo $(pids_na_porta "$p" | head -1)). Encerre-o manualmente e rode de novo."; ocupadas=1; }
  done
  [ "$ocupadas" = 1 ] && exit 1
  return 0
}

liberar_portas

# retaguarda PRIMEIRO: é o dono do Flyway (cria/atualiza o banco); os outros só validam o schema
for s in retaguarda-service:8084 catalogo-service:8081 estoque-service:8082 vendas-service:8083 tef-agent:8090; do
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

if [ "$APP_ENV" = producao ] && [ "$USE_NGINX" = true ]; then
  aguardar nginx 443
elif [ "$APP_ENV" = producao ]; then
  echo "Iniciando pdv-web (vite preview)..."
  (cd pdv-web && exec npx vite preview --host --port 5173) > logs/pdv-web.log 2>&1 &
  PIDS+=($!); aguardar pdv-web 5173
else
  echo "Iniciando pdv-web..."
  (cd pdv-web && { [ -d node_modules ] || npm install; } && exec npm run dev -- --host) > logs/pdv-web.log 2>&1 &
  PIDS+=($!); aguardar pdv-web 5173
fi

echo
if [ "$APP_ENV" = producao ]; then
  echo "🍎 Tudo no ar (producao): $FRONT_URL"
  echo "🔐 Keycloak: $KEYCLOAK_URL  (usuários iniciais: gerente/supervisor/operador, senha temporária 'trocar123')"
else
  echo "🍎 Tudo no ar (desenvolvimento): http://localhost:5173"
  echo "🔑 Login local: gerente/gerente, supervisor/supervisor, operador/operador"
fi
echo "Logs em ./logs  —  Ctrl+C para parar"
wait
