#!/usr/bin/env bash
# Para tudo caso o iniciar.sh tenha sido encerrado de forma abrupta.
for porta in 8081 8082 8083 8084 5173; do
  pid=$(lsof -ti tcp:$porta 2>/dev/null) && kill $pid && echo "Parado processo na porta $porta"
done
