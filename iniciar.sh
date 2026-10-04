#!/usr/bin/env bash
# Inicia catalogo, estoque, vendas (Quarkus dev) e o PDV (React). Ctrl+C encerra tudo.
set -u
cd "$(dirname "$0")"
mkdir -p logs; PIDS=()

for cmd in java mvn node npm; do
  command -v $cmd >/dev/null || { echo "❌ '$cmd' não encontrado no PATH"; exit 1; }
done

cleanup() { echo; echo "Encerrando serviços..."; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; wait 2>/dev/null; exit 0; }
trap cleanup INT TERM

aguardar() { # $1=nome $2=porta
  for i in $(seq 1 90); do
    (echo > /dev/tcp/127.0.0.1/$2) >/dev/null 2>&1 && { echo "✔ $1 no ar (porta $2)"; return; }
    sleep 2
  done
  echo "⚠ $1 não respondeu em 3 min — veja logs/$1.log"
}

for s in catalogo-service:8081 estoque-service:8082 vendas-service:8083 retaguarda-service:8084; do
  nome=${s%%:*}; porta=${s##*:}
  echo "Iniciando $nome..."
  (cd "$nome" && exec mvn -q quarkus:dev -Dquarkus.console.enabled=false -Ddebug=false) > "logs/$nome.log" 2>&1 &
  PIDS+=($!)
  aguardar "$nome" "$porta"
done

echo "Iniciando pdv-web..."
(cd pdv-web && { [ -d node_modules ] || npm install; } && exec npm run dev -- --host) > logs/pdv-web.log 2>&1 &
PIDS+=($!)
aguardar pdv-web 5173

echo
echo "🍎 Tudo no ar: PDV http://localhost:5173 | Swagger http://localhost:8081/q/swagger-ui"
echo "Logs em ./logs  —  Ctrl+C para parar"
wait
