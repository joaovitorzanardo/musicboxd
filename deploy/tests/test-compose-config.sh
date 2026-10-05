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
echo "prod compose config OK"
