#!/usr/bin/env bash
# Inicia a Fruteira em PRODUÇÃO (sem Maven/compilação): PostgreSQL + Keycloak (Docker), 5 serviços Java (jar) e o front.
#   USE_NGINX=true  -> HTTPS com nginx (rode antes ./ssl.sh letsencrypt)
#   USE_NGINX=false -> front por servidor-web.mjs em http://localhost:5173 (sem HTTPS)
set -u
cd "$(dirname "$0")"
mkdir -p logs; PIDS=()
# ---------- 1) arquivos necessários: se faltar nesta pasta, procura na raiz do projeto (pasta acima) ----------
RAIZ="$(cd .. 2>/dev/null && pwd)"
copiar_da_raiz() {   # $1 = caminho relativo; $2.. = outros lugares onde procurar (relativos à raiz)
  local rel="$1"; shift; [ -e "$rel" ] && return 0
  for cand in "$rel" "$@"; do
    if [ -e "$RAIZ/$cand" ]; then mkdir -p "$(dirname "$rel")"; cp -r "$RAIZ/$cand" "$rel"; echo "ℹ $rel não estava aqui: copiado de $RAIZ/$cand"; return 0; fi
  done
  return 1
}
faltou=0
copiar_da_raiz docker-compose.prod.yml || { echo "❌ docker-compose.prod.yml não encontrado aqui nem em $RAIZ"; faltou=1; }
[ -f docker-compose.prod.yml ] && sed -i.bak 's#\./pdv-web/dist#./web#' docker-compose.prod.yml && rm -f docker-compose.prod.yml.bak   # a pasta de produção serve o front de ./web
copiar_da_raiz keycloak || { echo "❌ pasta keycloak (realm) não encontrada aqui nem em $RAIZ"; faltou=1; }
copiar_da_raiz nginx/templates || echo "ℹ nginx/templates ausente (só é necessário com USE_NGINX=true)"
copiar_da_raiz nginx/snippets || echo "ℹ nginx/snippets ausente (só é necessário com USE_NGINX=true)"
copiar_da_raiz database || true
copiar_da_raiz servidor-web.mjs servidor-web.mjs producao-modelo/servidor-web.mjs || echo "ℹ servidor-web.mjs ausente (só é necessário sem nginx)"
if [ ! -d web ]; then copiar_da_raiz web pdv-web/dist || echo "ℹ front compilado (web/) ausente: rode gerar-producao na raiz do projeto"; fi
if [ ! -d servicos ]; then echo "ℹ servicos/ (jars) ausente: rode gerar-producao na raiz do projeto"; fi
mkdir -p nginx/certs logs backups
if [ ! -f .env ]; then
  if   [ -f "$RAIZ/.env" ];         then fonte="$RAIZ/.env"
  elif [ -f "$RAIZ/.env.example" ]; then fonte="$RAIZ/.env.example"
  else echo "❌ .env não encontrado aqui nem em $RAIZ"; exit 1; fi
  cp "$fonte" .env; echo "ℹ .env criado a partir de $fonte"
  sed -i.bak 's/^APP_ENV=.*/APP_ENV=producao/' .env && rm -f .env.bak
fi
[ "$faltou" = 1 ] && exit 1

# ---------- 2) senhas: valor vazio ou de exemplo ("troque-...") vira uma senha aleatória gravada no .env ----------
gerar_senha() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "${1:-24}"; }
valor_env() { grep -E "^$1=" .env | tail -1 | cut -d= -f2- | tr -d '\r'; }
definir_env() {   # $1=chave $2=valor: troca a linha ativa, ou a comentada, ou acrescenta
  local chave="$1" valor="$2"
  if grep -qE "^$chave=" .env; then awk -v k="$chave" -v v="$valor" 'BEGIN{FS=OFS="="} $1==k{print k"="v; next} {print}' .env > .env.tmp
  elif grep -qE "^#$chave=" .env; then awk -v k="$chave" -v v="$valor" 'BEGIN{FS="="} $1=="#"k && !feito{print k"="v; feito=1; next} {print}' .env > .env.tmp
  else { cat .env; printf '\n%s=%s\n' "$chave" "$valor"; } > .env.tmp; fi
  mv .env.tmp .env
}
for v in DB_PASS KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASS TEF_TOKEN; do
  atual="$(valor_env $v)"
  case "$atual" in ""|troque*)
    tam=24; [ "$v" = TEF_TOKEN ] && tam=40
    definir_env "$v" "$(gerar_senha $tam)"; echo "🔑 $v estava vazio/de exemplo: gerada uma senha aleatória e gravada no .env (guarde o arquivo!)";;
  esac
done
chmod 600 .env 2>/dev/null || true
[ "${1:-}" = "--preparar" ] && { echo "✔ Preparação concluída (--preparar): nada foi iniciado."; exit 0; }
set -a; . <(tr -d '\r' < .env); set +a
USE_NGINX=${USE_NGINX:-false}
need=(java docker); [ "$USE_NGINX" = true ] || need+=(node)
for c in "${need[@]}"; do command -v $c >/dev/null || { echo "❌ '$c' não encontrado no PATH"; exit 1; }; done
for v in DB_PASS KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_DB_PASS; do
  case "${!v:-}" in ""|troque*) echo "❌ Defina um valor seguro para $v no .env"; exit 1;; esac
done
PERFIL=()
if [ "$USE_NGINX" = true ]; then
  case "${FRONT_DOMAIN:-}${KEYCLOAK_DOMAIN:-}" in ""|*exemplo*) echo "❌ Troque FRONT_DOMAIN/KEYCLOAK_DOMAIN pelos domínios reais no .env"; exit 1;; esac
  [ -f nginx/certs/live/fruteira/fullchain.pem ] || { echo "❌ Sem certificado TLS. Rode ./ssl.sh letsencrypt (ou ./ssl.sh local)"; exit 1; }
  PERFIL=(--profile nginx)
fi
aguardar() { for i in $(seq 1 90); do (echo > /dev/tcp/127.0.0.1/$2) >/dev/null 2>&1 && { echo "✔ $1 no ar (porta $2)"; return; }; sleep 2; done; echo "⚠ $1 não respondeu em 3 min — veja logs/$1.log"; }
cleanup() { echo; echo "Encerrando serviços Java..."; for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; wait 2>/dev/null; exit 0; }
trap cleanup INT TERM

echo "Subindo PostgreSQL e Keycloak${USE_NGINX:+...}"
docker compose -f docker-compose.prod.yml ${PERFIL[@]+"${PERFIL[@]}"} up -d || exit 1
aguardar keycloak 8180
for s in catalogo:8081 estoque:8082 vendas:8083 retaguarda:8084 tef-agent:8090; do
  n=${s%%:*}; p=${s##*:}
  [ -f "servicos/$n/quarkus-run.jar" ] || { echo "❌ servicos/$n não encontrado (rode gerar-producao de novo)"; exit 1; }
  echo "Iniciando $n..."
  (cd "servicos/$n" && exec java ${JAVA_OPTS:--Xmx384m} -jar quarkus-run.jar) > "logs/$n.log" 2>&1 &
  PIDS+=($!); aguardar "$n" "$p"
done
if [ "$USE_NGINX" != true ]; then
  node servidor-web.mjs 5173 > logs/web.log 2>&1 &
  PIDS+=($!); aguardar web 5173
fi
echo; echo "🍎 Fruteira no ar: ${FRONT_URL:-http://localhost:5173}"
echo "🔐 Keycloak: ${KEYCLOAK_URL:-http://localhost:8180}  (gerente/supervisor/operador, senha temporária 'trocar123' — troque no primeiro acesso)"
echo "Logs em ./logs — Ctrl+C encerra os serviços Java (os contêineres seguem rodando; ./parar.sh para tudo)"
wait
