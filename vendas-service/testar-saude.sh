#!/usr/bin/env bash
# Testa só com o JDK as regras da rotina de saúde dos pagamentos (SaudeAvaliacao).
set -e
cd "$(dirname "$0")"
d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
B=src/main/java/br/com/fruteira/vendas; T=src/test/java/br/com/fruteira/vendas
javac -encoding UTF-8 -d "$d" $B/SaudeAvaliacao.java $T/SaudeTeste.java
java -Dstdout.encoding=UTF-8 -cp "$d" br.com.fruteira.vendas.SaudeTeste
