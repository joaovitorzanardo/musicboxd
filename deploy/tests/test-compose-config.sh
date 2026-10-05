#!/usr/bin/env bash
# deploy/tests/test-compose-config.sh
set -euo pipefail
cd "$(dirname "$0")/.."

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
printf 'DOMAIN=example.test\nGHCR_OWNER=someone\nIMAGE_TAG=latest\nCERTBOT_EMAIL=a@b.c\n' > "$TMP/stack.env"
printf 'POSTGRES_DB=musicboxd\nPOSTGRES_USER=musicboxd\nPOSTGRES_PASSWORD=x\n' > "$TMP/postgres.env"

OUT=$(MUSICBOXD_ENV_DIR="$TMP" docker compose --env-file "$TMP/stack.env" -f docker-compose.prod.yml config)

grep -q 'ghcr.io/someone/musicboxd-api:latest' <<<"$OUT"
grep -q 'ghcr.io/someone/musicboxd-web:latest' <<<"$OUT"
# Only nginx publishes ports, and only 80/443.
PUBLISHED=$(grep -E '^\s+published:' <<<"$OUT" | tr -d ' "' | sort | tr '\n' ' ')
[ "$PUBLISHED" = "published:443 published:80 " ] || { echo "unexpected published ports: $PUBLISHED"; exit 1; }
! grep -q 'POSTGRES_PASSWORD: musicboxd' <<<"$OUT"   # no hardcoded dev creds
# Missing GHCR_OWNER / DOMAIN must fail fast with a clear message (not render empty values).
if ERR=$(MUSICBOXD_ENV_DIR="$TMP" env -u GHCR_OWNER -u DOMAIN docker compose -f docker-compose.prod.yml config 2>&1 >/dev/null); then
  echo "compose config succeeded without GHCR_OWNER/DOMAIN"; exit 1
fi
grep -q 'must be set' <<<"$ERR" || { echo "missing-var error lacks 'must be set': $ERR"; exit 1; }
echo "missing-variable check OK"
echo "prod compose config OK"
