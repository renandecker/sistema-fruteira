#!/usr/bin/env bash
# Agente TEF deste computador (caixa com maquininha própria). Precisa de Java 21, .env e servicos/tef-agent/.
cd "$(dirname "$0")"
command -v java >/dev/null || { echo "❌ Java 21 não encontrado no PATH (https://adoptium.net)"; exit 1; }
exec java -jar fruteira-launcher.jar tef "$@"
