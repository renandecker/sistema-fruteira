#!/usr/bin/env bash
# Roda os testes do módulo fiscal só com o JDK (sem Maven, sem internet).
set -e
cd "$(dirname "$0")"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 -d "$OUT" src/main/java/br/com/fruteira/retaguarda/fiscal/*.java src/test/java/br/com/fruteira/retaguarda/fiscal/*.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" br.com.fruteira.retaguarda.fiscal.FiscalTeste
java -Dstdout.encoding=UTF-8 -cp "$OUT" br.com.fruteira.retaguarda.fiscal.ValidadorTeste
java -Dstdout.encoding=UTF-8 -cp "$OUT" br.com.fruteira.retaguarda.fiscal.NfeTeste
java -Dstdout.encoding=UTF-8 -cp "$OUT" br.com.fruteira.retaguarda.fiscal.CertificadoTeste
