#!/usr/bin/env bash
# Testes do Pix só com o JDK (sem Maven/Quarkus):
#  1) enums e utilitários; 2) as integrações reais (Mercado Pago, Asaas, Sicoob, Efí) contra um servidor HTTP local que imita o provedor.
# O passo 2 precisa do Jackson: informe JACKSON_DIR=pasta-com-os-jars ou rode depois de um "mvn package" (usa ~/.m2).
set -e
cd "$(dirname "$0")"
d=$(mktemp -d); trap 'rm -rf "$d"' EXIT
B=src/main/java/br/com/fruteira/vendas; T=src/test/java/br/com/fruteira/vendas
javac -encoding UTF-8 -d "$d/enums" $B/PixProvider.java $B/PixStatus.java $B/PixUtil.java $T/PixEnumsTeste.java
java -Dstdout.encoding=UTF-8 -cp "$d/enums" br.com.fruteira.vendas.PixEnumsTeste

CP=""
for dir in "${JACKSON_DIR:-}" "$HOME/.m2/repository/com/fasterxml/jackson" /opt/gradle*/lib; do
  [ -n "$dir" ] && [ -d "$dir" ] || continue
  for n in jackson-databind jackson-core jackson-annotations; do
    j=$(find "$dir" -name "$n-[0-9]*.jar" ! -name "*sources*" 2>/dev/null | sort | tail -1)
    [ -n "$j" ] && CP="$CP:$j"
  done
  [ "$(echo "$CP" | tr ':' '\n' | grep -c jackson)" -ge 3 ] && break || CP=""
done
if [ -z "$CP" ]; then echo "(pulando as integrações: Jackson não encontrado — defina JACKSON_DIR)"; exit 0; fi
CP="${CP#:}"
javac -encoding UTF-8 -cp "$CP" -d "$d/prov" $(find src/test/stubs -name '*.java') \
  $B/PixProvider.java $B/PixStatus.java $B/PixUtil.java $B/PixConfig.java $B/PixProviderService.java $B/BacenPixService.java \
  $B/EfiPixService.java $B/ItauPixService.java $B/BradescoPixService.java $B/SicoobPixService.java $B/SicrediPixService.java \
  $B/ApiKeyPixService.java $B/MercadoPagoPixService.java $B/AsaasPixService.java $T/PixProvidersTeste.java
java -Dstdout.encoding=UTF-8 -cp "$d/prov:$CP" br.com.fruteira.vendas.PixProvidersTeste
