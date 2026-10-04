#!/usr/bin/env bash
# Certificados TLS para o nginx (usa Docker; não precisa instalar openssl/certbot).
#   ./ssl.sh letsencrypt  -> certificado real (domínios com DNS apontando para este servidor; portas 80/443 liberadas)
#   ./ssl.sh renovar      -> renova (agende no cron, ex.: 1x por semana). O nginx fica parado alguns segundos.
#   ./ssl.sh local        -> autoassinado, somente para testes
set -eu
cd "$(dirname "$0")"
[ -f .env ] || { echo "❌ .env não encontrado"; exit 1; }
set -a; . <(tr -d '\r' < .env); set +a
: "${FRONT_DOMAIN:?defina FRONT_DOMAIN no .env}" "${KEYCLOAK_DOMAIN:?defina KEYCLOAK_DOMAIN no .env}"
COMPOSE="docker compose -f docker-compose.prod.yml"
mkdir -p nginx/certs/live/fruteira
parar_nginx() { $COMPOSE stop nginx >/dev/null 2>&1 || true; }
subir_nginx() { $COMPOSE up -d nginx >/dev/null 2>&1 || true; }

case "${1:-}" in
  letsencrypt)
    case "$FRONT_DOMAIN$KEYCLOAK_DOMAIN" in *exemplo*) echo "❌ Troque os domínios de exemplo no .env"; exit 1;; esac
    : "${LE_EMAIL:?defina LE_EMAIL no .env}"
    parar_nginx
    docker run --rm -p 80:80 -v "$PWD/nginx/certs:/etc/letsencrypt" certbot/certbot certonly --standalone \
      --cert-name fruteira -d "$FRONT_DOMAIN" -d "$KEYCLOAK_DOMAIN" --email "$LE_EMAIL" --agree-tos --no-eff-email --non-interactive
    subir_nginx; echo "✔ Certificado emitido." ;;
  renovar)
    parar_nginx
    docker run --rm -p 80:80 -v "$PWD/nginx/certs:/etc/letsencrypt" certbot/certbot renew --standalone
    subir_nginx; echo "✔ Renovação concluída." ;;
  local)
    docker run --rm -v "$PWD/nginx/certs:/certs" alpine/openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
      -keyout /certs/live/fruteira/privkey.pem -out /certs/live/fruteira/fullchain.pem \
      -subj "/CN=$FRONT_DOMAIN" -addext "subjectAltName=DNS:$FRONT_DOMAIN,DNS:$KEYCLOAK_DOMAIN"
    echo "✔ Certificado AUTOASSINADO gerado. Para testar localmente:"
    echo "  1) /etc/hosts (Windows: C:\\Windows\\System32\\drivers\\etc\\hosts):  127.0.0.1 $FRONT_DOMAIN $KEYCLOAK_DOMAIN"
    echo "  2) no .env: OIDC_TLS_VERIFICATION=none  (os serviços Java não confiam em certificado autoassinado)"
    echo "  3) aceite o aviso do navegador nos dois domínios" ;;
  *) echo "Uso: ./ssl.sh letsencrypt | renovar | local"; exit 1;;
esac
