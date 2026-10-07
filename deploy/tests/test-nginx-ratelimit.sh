#!/usr/bin/env bash
# deploy/tests/test-nginx-ratelimit.sh
# Boots the real local nginx config (no api needed: throttling and the body cap are
# enforced before proxying) and checks per-IP throttling and the request body cap.
set -euo pipefail
cd "$(dirname "$0")/../.."

HOST_PWD=$(cygpath -m "$PWD" 2>/dev/null || echo "$PWD")
NAME=mbd13-nginx-test
PORT=18089
BIG=$(mktemp)
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -f "$BIG"; }
trap cleanup EXIT
docker rm -f "$NAME" >/dev/null 2>&1 || true

# --add-host: nginx resolves the `api` upstream at load time.
MSYS_NO_PATHCONV=1 docker run -d --name "$NAME" --add-host api:127.0.0.1 -p "$PORT:80" \
  -v "$HOST_PWD/deploy/nginx/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.27-alpine >/dev/null
for _ in $(seq 1 20); do curl -s -o /dev/null "http://localhost:$PORT/" && break; sleep 0.5; done
sleep 3   # let the bucket refill after the readiness probes

count_codes() { # $1 = number of requests; prints "<ok> <429>"
  local ok=0 limited=0 code
  for _ in $(seq 1 "$1"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/")
    case "$code" in 200) ok=$((ok+1));; 429) limited=$((limited+1));; esac
  done
  echo "$ok $limited"
}

# A normal page load (20 quick requests) must not be throttled.
read -r OK LIMITED <<<"$(count_codes 20)"
[ "$LIMITED" -eq 0 ] || { echo "normal load throttled: $LIMITED of 20 got 429"; exit 1; }

sleep 3   # let the bucket refill
# A burst of 200 sequential requests from one IP must be throttled with 429 (not 503).
read -r OK LIMITED <<<"$(count_codes 200)"
[ "$LIMITED" -gt 0 ] || { echo "burst of 200 never got 429 (ok=$OK)"; exit 1; }
echo "burst throttled: ok=$OK limited=$LIMITED"

sleep 3
# Oversized body is rejected by nginx before it reaches the api.
head -c 2097152 /dev/zero > "$BIG"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary "@$BIG" \
  -H 'Content-Type: application/octet-stream' "http://localhost:$PORT/api/v1/anything")
[ "$CODE" = "413" ] || { echo "2 MiB body got $CODE, expected 413"; exit 1; }

# The local config forwards a client IP the caller cannot forge.
grep -q 'proxy_set_header X-Forwarded-For \$remote_addr;' deploy/nginx/nginx.conf \
  || { echo "nginx.conf does not overwrite X-Forwarded-For"; exit 1; }
echo "nginx rate limit OK"
