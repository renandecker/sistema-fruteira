#!/usr/bin/env bash
# Inicia a Fruteira em DESENVOLVIMENTO: 4 serviços Quarkus em modo dev (H2, sem Docker) + Vite.
# PRODUÇÃO (PostgreSQL + Keycloak sem Docker, só Java) não passa por aqui: ./gerar-producao.sh e depois producao/iniciar.sh
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
if [ "$APP_ENV" = producao ]; then
  echo "ℹ A produção não usa este script nem Docker: rode ./gerar-producao.sh (gera a pasta producao/) e depois producao/iniciar.sh — só Java 21 e PostgreSQL na máquina."
  exit 1
fi
echo "▶ Ambiente: $APP_ENV"

need=(java mvn node npm)
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

# Desenvolvimento: banco H2 em arquivo (data/fruteira). Sobra de execução anterior (processo morto) deixa um .lock.db que trava a conexão;
# como as portas dos serviços acabaram de ser liberadas, nenhum serviço nosso está com o banco aberto e o lock pode ser removido.
if [ "$APP_ENV" != producao ]; then
  mkdir -p data; rm -f data/*.lock.db 2>/dev/null
  case "$(pwd -P)" in *[Oo]ne[Dd]rive*) echo "⚠ O projeto está dentro do OneDrive: a sincronização pode travar o arquivo do banco (data/fruteira.mv.db). Se der erro de banco, mova a pasta para fora do OneDrive (ex.: C:\\fruteira) ou defina H2_ARQUIVO no .env.";; esac
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
echo "Logs em ./logs  —  Ctrl+C para parar"
wait
