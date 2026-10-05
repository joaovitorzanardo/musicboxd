#!/usr/bin/env bash
# Renews due certificates over the webroot (nginx keeps serving) and reloads nginx only if one renewed.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)
MARKER=/etc/letsencrypt/.renewed   # inside the certbot container's volume

"${COMPOSE[@]}" run --rm --no-deps --entrypoint sh certbot -c "rm -f $MARKER"
"${COMPOSE[@]}" run --rm --no-deps certbot renew --webroot -w /var/www/certbot \
  --deploy-hook "touch $MARKER" "$@"

if "${COMPOSE[@]}" run --rm --no-deps --entrypoint sh certbot -c "test -f $MARKER"; then
  "${COMPOSE[@]}" exec -T nginx nginx -s reload
  echo "Certificate renewed; nginx reloaded."
else
  echo "No certificate due for renewal."
fi
