#!/usr/bin/env bash
# Renews due certificates over the webroot (nginx keeps serving) and reloads nginx only if one renewed.
# The marker is cleared only after a successful reload, so a failed reload is retried on the next run
# (a stale marker at worst causes one harmless extra reload).
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)
MARKER=/etc/letsencrypt/.renewed   # inside the certbot container's volume

"${COMPOSE[@]}" run --rm -T --no-deps certbot renew --webroot -w /var/www/certbot \
  --deploy-hook "touch $MARKER" "$@"

if "${COMPOSE[@]}" run --rm -T --no-deps --entrypoint sh certbot -c "test -f $MARKER"; then
  "${COMPOSE[@]}" exec -T nginx nginx -s reload
  "${COMPOSE[@]}" run --rm -T --no-deps --entrypoint sh certbot -c "rm -f $MARKER"
  echo "Certificate renewed; nginx reloaded."
else
  echo "No certificate due for renewal."
fi
