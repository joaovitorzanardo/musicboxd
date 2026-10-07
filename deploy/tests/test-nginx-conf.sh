#!/usr/bin/env bash
# deploy/tests/test-nginx-conf.sh
# Renders the prod template with a throwaway cert and runs `nginx -t` on it.
set -euo pipefail
cd "$(dirname "$0")/../.."

DOMAIN=example.test
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/live/$DOMAIN"
PRIVKEY="$TMP/live/$DOMAIN/privkey.pem"
FULLCHAIN="$TMP/live/$DOMAIN/fullchain.pem"
# openssl on MSYS2/Git Bash needs Windows paths; no-op on Linux/macOS
PRIVKEY_OSSL=$(cygpath -w "$PRIVKEY" 2>/dev/null || echo "$PRIVKEY")
FULLCHAIN_OSSL=$(cygpath -w "$FULLCHAIN" 2>/dev/null || echo "$FULLCHAIN")
# Git Bash rewrites a leading "/CN=" into a path; MSYS2_ARG_CONV_EXCL stops that. Real openssl errors stay visible and fatal.
MSYS2_ARG_CONV_EXCL='/CN=' openssl req -quiet -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$DOMAIN" \
  -keyout "$PRIVKEY_OSSL" -out "$FULLCHAIN_OSSL"

# Convert paths to Windows format for docker on MSYS2; no-op on Linux/macOS
HOST_PWD=$(cygpath -m "$PWD" 2>/dev/null || echo "$PWD")
HOST_TMP=$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")

# --add-host: nginx -t resolves the `api` upstream at load time.
MSYS_NO_PATHCONV=1 docker run --rm --add-host api:127.0.0.1 \
  -e DOMAIN="$DOMAIN" -e NGINX_ENVSUBST_FILTER=DOMAIN \
  -v "$HOST_PWD/deploy/nginx/nginx.prod.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  -v "$HOST_TMP:/etc/letsencrypt:ro" \
  nginx:1.27-alpine sh -c '/docker-entrypoint.d/20-envsubst-on-templates.sh >/dev/null && nginx -t && nginx -T 2>/dev/null' > "$TMP/out.txt"

grep -q 'listen 443 ssl' "$TMP/out.txt"
grep -q "ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem" "$TMP/out.txt"
grep -q 'location /.well-known/acme-challenge/' "$TMP/out.txt"
grep -q 'return 301 https://' "$TMP/out.txt"
grep -q 'try_files $uri $uri/ /index.html' "$TMP/out.txt"
grep -q 'proxy_set_header X-Forwarded-Proto $scheme' "$TMP/out.txt"
grep -q 'limit_req_zone $binary_remote_addr zone=perip' "$TMP/out.txt"
grep -q 'limit_req zone=perip' "$TMP/out.txt"
grep -q 'limit_req_status 429' "$TMP/out.txt"
grep -q 'client_max_body_size 1m' "$TMP/out.txt"
grep -q 'proxy_set_header X-Forwarded-For $remote_addr' "$TMP/out.txt"
echo "nginx prod config OK"
