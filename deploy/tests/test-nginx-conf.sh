#!/usr/bin/env bash
# deploy/tests/test-nginx-conf.sh
# Renders the prod template with a throwaway cert and runs `nginx -t` on it.
set -euo pipefail
cd "$(dirname "$0")/../.."

DOMAIN=example.test
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/live/$DOMAIN"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$DOMAIN" \
  -keyout "$TMP/live/$DOMAIN/privkey.pem" -out "$TMP/live/$DOMAIN/fullchain.pem" 2>/dev/null

# --add-host: nginx -t resolves the `api` upstream at load time.
docker run --rm --add-host api:127.0.0.1 \
  -e DOMAIN="$DOMAIN" -e NGINX_ENVSUBST_FILTER=DOMAIN \
  -v "$PWD/deploy/nginx/nginx.prod.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  -v "$TMP:/etc/letsencrypt:ro" \
  nginx:1.27-alpine sh -c '/docker-entrypoint.d/20-envsubst-on-templates.sh >/dev/null && nginx -t && nginx -T 2>/dev/null' > "$TMP/out.txt"

grep -q 'listen 443 ssl' "$TMP/out.txt"
grep -q "ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem" "$TMP/out.txt"
grep -q 'location /.well-known/acme-challenge/' "$TMP/out.txt"
grep -q 'return 301 https://' "$TMP/out.txt"
grep -q 'try_files $uri $uri/ /index.html' "$TMP/out.txt"
grep -q 'proxy_set_header X-Forwarded-Proto $scheme' "$TMP/out.txt"
echo "nginx prod config OK"
