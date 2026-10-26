#!/usr/bin/env bash
# Fruteira (produção, só Java + PostgreSQL): parar
cd "$(dirname "$0")"
command -v java >/dev/null || { echo "❌ Java 21 não encontrado no PATH (instale o JRE/JDK 21: https://adoptium.net)"; exit 1; }
exec java -jar fruteira-launcher.jar parar "$@"
