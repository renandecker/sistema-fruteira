#!/usr/bin/env bash
# Gera a pasta ./producao (ou o ZIP) para instalar no servidor: só precisa de Java 21 + PostgreSQL lá.
#   ./gerar-producao.sh              monta producao/ (front, launcher, 5 jars Quarkus, Keycloak)
#   ./gerar-producao.sh --zip        idem e cria fruteira-producao.zip
#   ./gerar-producao.sh --sem-jars   não compila os serviços Java (sem Maven)
#   ./gerar-producao.sh --com-testes roda os testes do Maven
# Na máquina de desenvolvimento: JDK 21, Maven, Node/npm. O servidor de destino: Java 21 + PostgreSQL.
set -eu
cd "$(dirname "$0")"
DEST=producao; ZIP=0; JARS=1; TESTES=-DskipTests; KC_VER=26.0.8
for a in "$@"; do case $a in --zip) ZIP=1;; --sem-jars) JARS=0;; --com-testes) TESTES=;; *) echo "opção desconhecida: $a"; exit 1;; esac; done
need="java node npm"; [ $JARS = 1 ] && need="$need mvn"
for c in $need; do command -v $c >/dev/null || { echo "❌ '$c' não encontrado no PATH"; exit 1; }; done
mkdir -p "$DEST"/{servicos,logs,backups,run}

echo "▶ Front..."
(cd pdv-web && { [ -d node_modules ] || npm install --silent; } && APP_ENV=producao npm run build --silent) || { echo "❌ build do front falhou"; exit 1; }
rm -rf "$DEST/web"; cp -r pdv-web/dist "$DEST/web"       # a URL do Keycloak é lida em /config.js (do .env), não vai no build

echo "▶ Launcher..."
./launcher/build.sh "$(pwd)/$DEST" >/dev/null

if [ $JARS = 1 ]; then
  for par in catalogo-service:catalogo estoque-service:estoque vendas-service:vendas retaguarda-service:retaguarda tef-agent:tef-agent; do
    src=${par%%:*}; dst=${par##*:}; echo "▶ $src..."
    (cd "$src" && mvn -q package $TESTES) || { echo "❌ build de $src falhou"; exit 1; }
    [ -f "$src/target/quarkus-app/quarkus-run.jar" ] || { echo "❌ jar de $src não gerado"; exit 1; }
    rm -rf "$DEST/servicos/$dst"; mkdir -p "$DEST/servicos/$dst"; cp -r "$src/target/quarkus-app/." "$DEST/servicos/$dst/"
  done
else echo "ℹ --sem-jars: servicos/ não foi gerada"; fi

if [ ! -f "$DEST/keycloak/bin/kc.sh" ]; then
  echo "▶ Keycloak $KC_VER..."
  T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
  curl -fsSL -o "$T/kc.zip" "https://github.com/keycloak/keycloak/releases/download/$KC_VER/keycloak-$KC_VER.zip" || { echo "❌ download do Keycloak falhou (baixe keycloak-$KC_VER.zip e extraia em $DEST/keycloak)"; exit 1; }
  unzip -q "$T/kc.zip" -d "$T" && rm -rf "$DEST/keycloak" && mv "$T/keycloak-$KC_VER" "$DEST/keycloak"
fi
mkdir -p "$DEST/keycloak/data/import"; cp keycloak/fruteira-realm.json "$DEST/keycloak/data/import/"

cp producao-modelo/* "$DEST/"; cp .env.example "$DEST/.env.example"
[ -f certificado.pfx ] && [ ! -f "$DEST/certificado.pfx" ] && cp certificado.pfx "$DEST/" && echo "✔ certificado.pfx copiado"
chmod +x "$DEST"/*.sh
echo "✅ Pasta pronta: $(pwd)/$DEST ($(du -sh "$DEST" | cut -f1))"
if [ $ZIP = 1 ]; then
  rm -f fruteira-producao.zip; (cd "$DEST" && zip -qr ../fruteira-producao.zip . -x '.env' 'logs/*' 'run/*' 'backups/*')
  echo "✅ fruteira-producao.zip criado (sem .env: ele nasce na 1ª execução com senhas geradas)"
fi
