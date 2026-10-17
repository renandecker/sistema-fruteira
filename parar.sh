#!/usr/bin/env bash
# Para tudo caso o iniciar.sh tenha sido encerrado de forma abrupta.
cd "$(dirname "$0")"
PORTAS="8081 8082 8083 8084 8090 5173"
grep -qiE '^USAR_NGROK=true' .env 2>/dev/null && PORTAS="$PORTAS 4040"   # agente do Ngrok iniciado pelo iniciar.sh
for porta in $PORTAS; do
  pids=$(lsof -t -iTCP:$porta -sTCP:LISTEN 2>/dev/null || fuser $porta/tcp 2>/dev/null)
  for pid in $pids; do
    pai=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
    kill "$pid" 2>/dev/null; for i in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -9 "$pid" 2>/dev/null
    case "$(ps -o comm= -p "${pai:-0}" 2>/dev/null)" in mvn|java|npm|node|npx) kill "$pai" 2>/dev/null;; esac
    echo "Parado processo $pid na porta $porta"
  done
done
if [ -f docker-compose.prod.yml ] && command -v docker >/dev/null && docker compose -f docker-compose.prod.yml --profile nginx ps -q 2>/dev/null | grep -q .; then
  docker compose -f docker-compose.prod.yml --profile nginx stop && echo "PostgreSQL/Keycloak parados"
fi
