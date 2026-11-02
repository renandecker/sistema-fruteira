#!/usr/bin/env bash
# Inicia a Fruteira LOCALMENTE (máquina de desenvolvimento):
#   APP_ENV=desenvolvimento (padrão) -> serviços Quarkus em modo dev + Vite; banco PostgreSQL em DOCKER (docker-compose.yml). USAR_DOCKER=false no .env -> H2 em arquivo, sem Docker.
#   APP_ENV=producao                 -> teste local do perfil de produção: PostgreSQL em DOCKER (docker-compose.yml), jars e front buildado, com login próprio (token JWT).
# A PRODUÇÃO REAL não usa Docker nem este script: ./gerar-producao.sh e depois producao/iniciar.sh (só Java 21 + PostgreSQL).
set -u
cd "$(dirname "$0")"
mkdir -p logs data; PIDS=()

[ -f .env ] || { echo "❌ Arquivo .env não encontrado (copie de .env.example)"; exit 1; }
set -a; . <(tr -d '\r' < .env); set +a
# Certificado A1 da NF-e/NFC-e: certificado.pfx na raiz do projeto (ao lado do .env). FISCAL_CERT_PATH só é preciso para outro caminho/nome.
export FRUTEIRA_HOME="$PWD"
[ -z "${FISCAL_CERT_PATH:-}" ] && [ -f "$PWD/certificado.pfx" ] && export FISCAL_CERT_PATH="$PWD/certificado.pfx"
case "${FISCAL_CERT_PATH:-}" in ""|/*) ;; *) [ -f "$PWD/$FISCAL_CERT_PATH" ] && export FISCAL_CERT_PATH="$PWD/$FISCAL_CERT_PATH";; esac     # relativo → absoluto (os serviços rodam dentro da própria pasta)
APP_ENV=${APP_ENV:-desenvolvimento}
case "$APP_ENV" in desenvolvimento|producao) ;; *) echo "❌ APP_ENV inválido: '$APP_ENV' (use desenvolvimento ou producao)"; exit 1;; esac
echo "▶ Ambiente: $APP_ENV"

USAR_DOCKER=${USAR_DOCKER:-true}
need=(java mvn node npm); { [ "$APP_ENV" = producao ] || [ "$USAR_DOCKER" != false ]; } && need+=(docker)
for cmd in "${need[@]}"; do command -v $cmd >/dev/null || { echo "❌ '$cmd' não encontrado no PATH"; exit 1; }; done

cleanup() { echo; echo "Encerrando serviços..."; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; wait 2>/dev/null; exit 0; }
trap cleanup INT TERM

aguardar() { # $1=nome $2=porta
  for i in $(seq 1 120); do
    (echo > /dev/tcp/127.0.0.1/$2) >/dev/null 2>&1 && { echo "✔ $1 no ar (porta $2)"; return; }
    sleep 2
  done
  echo "⚠ $1 não respondeu em 4 min — veja logs/$1.log"
  return 1
}

aguardar_pronto() { # $1=nome $2=porta: espera resposta HTTP < 500 (em dev, serviço quebrado responde 500 na recuperação)
  for i in $(seq 1 120); do
    c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy "*" --max-time 3 "http://127.0.0.1:$2/" 2>/dev/null); [ "${c:-000}" -gt 0 ] 2>/dev/null && [ "${c:-000}" -lt 500 ] && { echo "✔ $1 pronto (porta $2)"; return 0; }
    sleep 2
  done
  echo "⚠ $1 não ficou pronto em 4 min — veja logs/$1.log"; return 1
}

# Portas do sistema: 4 serviços, agente TEF e front. Processo antigo (execução anterior que não foi encerrada) nelas faz o serviço novo
# falhar com "porta em uso" e o front responder 500. Antes de subir, quem estiver ESCUTANDO nelas é encerrado.
Para desligar: LIBERAR_PORTAS=false no .env.
PORTAS_APP=(8081 8082 8083 8084 8090 5173)
[ "${USAR_NGROK:-false}" = true ] && PORTAS_APP+=(4040)   # agente do Ngrok (painel/API local), que este script inicia
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

# Desenvolvimento: banco PostgreSQL em Docker (padrão) ou H2 em arquivo (USAR_DOCKER=false).
if [ "$APP_ENV" != producao ]; then
  if [ "$USAR_DOCKER" != false ]; then
    export DB_USER=${DB_USER:-fruteira} DB_PASS=${DB_PASS:-fruteira}
    export PG_DOCKER_PORT=${PG_DOCKER_PORT:-5442}
    export DEV_DB_KIND=postgresql DEV_DB_URL="jdbc:postgresql://localhost:$PG_DOCKER_PORT/fruteira"
    echo "▶ Docker (local): PostgreSQL..."
    docker compose up -d postgres || { echo "❌ docker compose falhou (o Docker está rodando? Sem Docker: USAR_DOCKER=false no .env)"; exit 1; }
    for i in $(seq 1 30); do [ "$(docker compose ps --format '{{.Health}}' postgres 2>/dev/null)" = healthy ] && break; sleep 2; done
    [ "$(docker compose ps --format '{{.Health}}' postgres 2>/dev/null)" = healthy ] || { echo "❌ PostgreSQL do Docker não ficou saudável: docker compose logs postgres"; exit 1; }
    echo "✔ PostgreSQL no ar (Docker, porta $PG_DOCKER_PORT)"
  else
    mkdir -p data; rm -f data/*.lock.db 2>/dev/null
    export DEV_DB_KIND=h2 DEV_DB_URL="jdbc:h2:file:${H2_ARQUIVO:-../data/fruteira};AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1"
    case "$(pwd -P)" in *[Oo]ne[Dd]rive*) echo "⚠ O projeto está no OneDrive: o H2 em arquivo pode travar. Prefira o Docker (padrão) ou defina H2_ARQUIVO fora do OneDrive.";; esac
  fi
fi

# Ngrok (opcional, USAR_NGROK=true): túnel HTTPS público só para o webhook do Pix (porta 8083). O endereço (muda a cada início no plano grátis,
# a menos que NGROK_DOMINIO esteja definido) vai para o vendas-service em NGROK_URL e a URL do webhook é montada a partir dele.
# Pelo túnel só o POST /pix/webhook responde (NgrokFilter); o restante da API continua fechado.
if [ "${USAR_NGROK:-false}" = true ]; then
  command -v ngrok >/dev/null || { echo "❌ USAR_NGROK=true, mas o 'ngrok' não está no PATH (https://ngrok.com/download e depois: ngrok config add-authtoken SEU_TOKEN, ou NGROK_AUTHTOKEN no .env)"; exit 1; }
  nargs=(http 8083 --log=stdout); [ -n "${NGROK_DOMINIO:-}" ] && nargs+=(--url="$NGROK_DOMINIO")
  echo "Iniciando o túnel Ngrok (porta 8083)..."
  # shellcheck disable=SC2086
  ngrok "${nargs[@]}" ${NGROK_ARGS:-} > logs/ngrok.log 2>&1 &
  PIDS+=($!)
  NGROK_DETECTADA=""
  for i in $(seq 1 20); do
    NGROK_DETECTADA=$(curl -s --noproxy "*" --max-time 2 http://127.0.0.1:4040/api/tunnels 2>/dev/null | grep -o '"public_url": *"https://[^"]*' | head -1 | cut -d'"' -f4)
    [ -n "$NGROK_DETECTADA" ] && break; sleep 1
  done
  if [ -n "$NGROK_DETECTADA" ]; then
    export NGROK_URL="$NGROK_DETECTADA"; export NGROK_API=http://127.0.0.1:4040/api/tunnels
    echo "🌐 Ngrok no ar: $NGROK_DETECTADA  →  webhook do Pix: $NGROK_DETECTADA${NGROK_CAMINHO:-/pix/webhook}?token=***"
    echo "   Pix real: registre essa URL no provedor (Configurações → PIX → Registrar webhook) ou use PIX_WEBHOOK_AUTO_REGISTRAR=true."
  else
    echo "⚠ O Ngrok não informou o endereço do túnel em 20 s — veja logs/ngrok.log (authtoken? domínio?). O Pix seguirá sem webhook (consulta a cada 10 s)."
  fi
fi

if [ "$APP_ENV" = producao ]; then
  for k in DB_PASS JWT_SEGREDO ADMIN_SENHA_INICIAL; do case "${!k:-}" in ""|troque*) echo "❌ Defina um valor seguro para $k no .env"; exit 1;; esac; done
  export PG_DOCKER_PORT=${PG_DOCKER_PORT:-5442} DB_USER=${DB_USER:-fruteira}; export DB_URL="jdbc:postgresql://localhost:$PG_DOCKER_PORT/fruteira"
  [ "${#JWT_SEGREDO}" -ge 32 ] || { echo "❌ JWT_SEGREDO precisa ter pelo menos 32 caracteres"; exit 1; }
  mkdir -p run; printf '{"kty":"oct","alg":"HS256","use":"sig","k":"%s"}\n' "$(printf %s "$JWT_SEGREDO" | base64 -w0 2>/dev/null | tr '+/' '-_' | tr -d '=')" > run/jwt.jwk; chmod 600 run/jwt.jwk
  export JWT_VERIFY_JWK="$PWD/run/jwt.jwk" JWT_SEGREDO ADMIN_SENHA_INICIAL
  echo "▶ Docker (local): PostgreSQL..."
  docker compose up -d || { echo "❌ docker compose falhou (o Docker está rodando?)"; exit 1; }
  aguardar PostgreSQL "$PG_DOCKER_PORT" || exit 1
  # retaguarda PRIMEIRO (Flyway); jars do perfil prod
  for s in retaguarda-service:8084 catalogo-service:8081 estoque-service:8082 vendas-service:8083 tef-agent:8090; do
    nome=${s%%:*}; porta=${s##*:}
    echo "Compilando e iniciando $nome..."
    (cd "$nome" && mvn -q package -DskipTests) > "logs/$nome-build.log" 2>&1 || { echo "❌ build de $nome falhou: logs/$nome-build.log"; tail -n 20 "logs/$nome-build.log"; cleanup; }
    (cd "$nome" && exec java ${JAVA_OPTS:--Xmx384m} -jar target/quarkus-app/quarkus-run.jar) > "logs/$nome.log" 2>&1 &
    PIDS+=($!)
    aguardar "$nome" "$porta" || { tail -n 25 "logs/$nome.log"; [ "$nome" = retaguarda-service ] && cleanup; }
  done
  echo "Compilando e iniciando pdv-web (preview)..."
  (cd pdv-web && { [ -d node_modules ] || npm install; } && APP_ENV=producao npm run build && exec npx vite preview --host --port 5173) > logs/pdv-web.log 2>&1 &
  PIDS+=($!); aguardar pdv-web 5173
  echo; echo "🍎 No ar (produção LOCAL com Docker): ${FRONT_URL:-http://localhost:5173}  —  entre como gerente com ADMIN_SENHA_INICIAL"
  echo "Docker fica de pé ao encerrar; para derrubar: docker compose down  (dados: docker compose down -v)"; wait; exit 0
fi

# retaguarda PRIMEIRO: é o dono do Flyway (cria/atualiza o banco); os outros só validam o schema
for s in retaguarda-service:8084 catalogo-service:8081 estoque-service:8082 vendas-service:8083 tef-agent:8090; do
  nome=${s%%:*}; porta=${s##*:}
  echo "Iniciando $nome..."
  (cd "$nome" && exec mvn -q quarkus:dev -Dquarkus.console.enabled=false -Ddebug=false) > "logs/$nome.log" 2>&1 &
  PIDS+=($!)
  if ! aguardar "$nome" "$porta" && [ "$nome" = retaguarda-service ]; then
    echo "❌ O retaguarda-service é o dono do banco (Flyway); sem ele os outros serviços falham com 'missing table'. Últimas linhas de logs/$nome.log:"
    tail -n 25 "logs/$nome.log"; cleanup
  fi
done

echo "Iniciando pdv-web..."
(cd pdv-web && { [ -d node_modules ] || npm install; } && exec npm run dev -- --host) > logs/pdv-web.log 2>&1 &
PIDS+=($!); aguardar pdv-web 5173

echo
echo "🍎 Tudo no ar (desenvolvimento): http://localhost:5173"
echo "🔑 Login local: gerente/gerente, supervisor/supervisor, operador/operador"
[ "$USAR_DOCKER" != false ] && echo "🐘 Banco: PostgreSQL no Docker (docker compose stop para pausar; down -v apaga os dados)"
echo "Logs em ./logs  —  Ctrl+C para parar"
wait
