#!/usr/bin/env bash
# Para tudo caso o iniciar.sh tenha sido encerrado de forma abrupta.
cd "$(dirname "$0")"
for porta in 8081 8082 8083 8084 5173; do
  pid=$(lsof -ti tcp:$porta 2>/dev/null) && kill $pid && echo "Parado processo na porta $porta"
done
if [ -f docker-compose.prod.yml ] && command -v docker >/dev/null && docker compose -f docker-compose.prod.yml --profile nginx ps -q 2>/dev/null | grep -q .; then
  docker compose -f docker-compose.prod.yml --profile nginx stop && echo "PostgreSQL/Keycloak parados"
fi
