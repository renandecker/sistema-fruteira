#!/usr/bin/env bash
# Gera a pasta ./producao pronta para instalar no servidor: jars dos serviços, front compilado, Keycloak, nginx,
# banco, .env com senhas aleatórias e scripts iniciar/parar/backup.
#   ./gerar-producao.sh            -> compila e monta (reaproveita producao/.env se já existir)
#   ./gerar-producao.sh --sem-testes (padrão) | --com-testes
# Rode na máquina de desenvolvimento (precisa de java, mvn, node, npm). O servidor de destino só precisa de java, docker (e node sem nginx).
set -eu
cd "$(dirname "$0")"
DEST=producao
TESTES=-DskipTests; [ "${1:-}" = "--com-testes" ] && TESTES=
for c in java mvn node npm; do command -v $c >/dev/null || { echo "❌ '$c' não encontrado no PATH"; exit 1; }; done
mkdir -p "$DEST"/{servicos,logs,backups}

# ---------- 1) .env de produção (nunca sobrescreve) ----------
gerar_senha() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "${1:-24}"; }
if [ -f "$DEST/.env" ]; then
  echo "ℹ Mantendo $DEST/.env existente"
else
  [ -f .env.example ] || { echo "❌ .env.example não encontrado"; exit 1; }
  sed -e 's/^APP_ENV=.*/APP_ENV=producao/' \
      -e "s/^KEYCLOAK_ADMIN_PASSWORD=.*/KEYCLOAK_ADMIN_PASSWORD=$(gerar_senha)/" \
      -e "s/^KEYCLOAK_DB_PASS=.*/KEYCLOAK_DB_PASS=$(gerar_senha)/" \
      -e "s/^DB_PASS=.*/DB_PASS=$(gerar_senha)/" \
      -e "s/^#TEF_TOKEN=.*/TEF_TOKEN=$(gerar_senha 40)/" \
      .env.example > "$DEST/.env"
  chmod 600 "$DEST/.env" 2>/dev/null || true
  echo "✔ $DEST/.env criado com senhas aleatórias (revise KEYCLOAK_URL, FRONT_URL e domínios)"
fi

# ---------- 2) front (a URL do Keycloak é embutida no build) ----------
echo "▶ Compilando o front..."
set -a; . <(tr -d '\r' < "$DEST/.env"); set +a
(cd pdv-web && { [ -d node_modules ] || npm install --silent; } && APP_ENV=producao npm run build --silent) || { echo "❌ build do front falhou"; exit 1; }
rm -rf "$DEST/web"; cp -r pdv-web/dist "$DEST/web"

# ---------- 3) microsserviços ----------
for par in catalogo-service:catalogo estoque-service:estoque vendas-service:vendas retaguarda-service:retaguarda tef-agent:tef-agent; do
  src=${par%%:*}; dst=${par##*:}
  echo "▶ Compilando $src..."
  (cd "$src" && mvn -q package $TESTES) || { echo "❌ build de $src falhou"; exit 1; }
  [ -f "$src/target/quarkus-app/quarkus-run.jar" ] || { echo "❌ $src/target/quarkus-app/quarkus-run.jar não foi gerado"; exit 1; }
  rm -rf "$DEST/servicos/$dst"; mkdir -p "$DEST/servicos/$dst"; cp -r "$src/target/quarkus-app/." "$DEST/servicos/$dst/"
done

# ---------- 4) infraestrutura e scripts ----------
sed 's#\./pdv-web/dist#./web#' docker-compose.prod.yml > "$DEST/docker-compose.prod.yml"
rm -rf "$DEST/keycloak" "$DEST/nginx/templates" "$DEST/nginx/snippets" "$DEST/database"
mkdir -p "$DEST/nginx/certs" "$DEST/keycloak" "$DEST/database"
cp -r keycloak/. "$DEST/keycloak/"; cp -r nginx/templates nginx/snippets "$DEST/nginx/"; touch "$DEST/nginx/certs/.gitkeep"
cp database/pre_cadastro_frutas_verduras.sql "$DEST/database/"
cp producao-modelo/* "$DEST/"
cp ssl.sh ssl.bat "$DEST/"
chmod +x "$DEST"/*.sh

echo
echo "✅ Pasta pronta: $(pwd)/$DEST"
du -sh "$DEST" | cut -f1 | sed 's/^/   tamanho: /'
echo "   Próximos passos: copie a pasta para o servidor, revise o .env e rode ./iniciar.sh (veja LEIA-ME.txt)"
echo "   TEF_TOKEN do .env deve ser o mesmo informado em Configurações → Balança e periféricos nos caixas."
