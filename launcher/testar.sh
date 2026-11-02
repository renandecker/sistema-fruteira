#!/usr/bin/env bash
# Testa o launcher (só JDK). PostgreSQL de teste opcional: PGTEST_PORT=5433 PGTEST_PASS=senha ./testar.sh
set -e
cd "$(dirname "$0")"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 --release 21 -d "$OUT" src/br/com/fruteira/launcher/*.java test/br/com/fruteira/launcher/*.java
java -Dstdout.encoding=UTF-8 ${PGTEST_PORT:+-Dpg.port=$PGTEST_PORT -Dpg.pass=$PGTEST_PASS} -cp "$OUT" br.com.fruteira.launcher.LauncherTeste
