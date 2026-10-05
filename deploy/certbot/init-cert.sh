#!/usr/bin/env bash
# Issues the first certificate with certbot standalone on port 80, BEFORE nginx is started
# (nginx's 443 block cannot load without the cert files). Run on the host from /opt/musicboxd.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
set -a
# shellcheck source=/dev/null
. "$ENV_FILE"
set +a
: "${DOMAIN:?DOMAIN missing in $ENV_FILE}" "${CERTBOT_EMAIL:?CERTBOT_EMAIL missing in $ENV_FILE}"

STAGING=()
[ "${1:-}" = "--staging" ] && STAGING=(--staging)

COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)

if "${COMPOSE[@]}" run --rm -T --no-deps --entrypoint sh certbot -c "test -f /etc/letsencrypt/live/$DOMAIN/fullchain.pem"; then
  if [ ${#STAGING[@]} -eq 0 ] && \
    "${COMPOSE[@]}" run --rm -T --no-deps --entrypoint sh certbot -c "grep -q acme-staging /etc/letsencrypt/renewal/$DOMAIN.conf"; then
    cat >&2 <<MSG
WARNING: the existing certificate for $DOMAIN is a Let's Encrypt STAGING certificate
(browsers will not trust it). To replace it with a real one:
  1. stop nginx:  ${COMPOSE[*]} stop nginx
  2. wipe the certificate volume:
     ${COMPOSE[*]} run --rm -T --no-deps --entrypoint sh certbot -c "rm -rf /etc/letsencrypt/*"
  3. re-run this script WITHOUT --staging
MSG
    exit 1
  fi
  echo "Certificate for $DOMAIN already present; nothing to do."
  exit 0
fi

# -p 80:80 here instead of in the compose file so `up` never publishes port 80 twice.
"${COMPOSE[@]}" run --rm -T --no-deps -p 80:80 certbot certonly --standalone \
  -d "$DOMAIN" --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email --non-interactive "${STAGING[@]}"

echo "Certificate issued${STAGING:+ (STAGING: browsers will not trust it; delete the volume and re-run without --staging)}."
