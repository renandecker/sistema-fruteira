#!/usr/bin/env bash
# Para os serviços Java, o servidor web e os contêineres (os dados do banco ficam preservados nos volumes).
cd "$(dirname "$0")"
for porta in 8081 8082 8083 8084 8090 5173; do
  pid=$(lsof -ti tcp:$porta 2>/dev/null) && kill $pid && echo "Parado processo na porta $porta"
done
command -v docker >/dev/null && docker compose -f docker-compose.prod.yml --profile nginx stop && echo "PostgreSQL/Keycloak/nginx parados"
