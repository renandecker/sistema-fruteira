#!/usr/bin/env bash
# Testa a conta do caderno (fiado) só com o JDK (sem Maven, sem internet).
set -e
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
javac -encoding UTF-8 -d "$OUT" src/main/java/br/com/fruteira/retaguarda/SaldoCaderno.java src/test/java/br/com/fruteira/retaguarda/CadernoTeste.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" br.com.fruteira.retaguarda.CadernoTeste
