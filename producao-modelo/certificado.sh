#!/usr/bin/env bash
# Fruteira (produção): cria o certificado HTTPS da rede da loja. Uso: ./certificado.sh IP-OU-NOME-DO-SERVIDOR [outro nome] [--forcar]
cd "$(dirname "$0")"
command -v java >/dev/null || { echo "❌ Java 21 não encontrado no PATH (https://adoptium.net)"; exit 1; }
exec java -jar fruteira-launcher.jar certificado "$@"
