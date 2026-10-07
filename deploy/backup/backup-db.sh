#!/usr/bin/env bash
# Dumps the production database (pg_dump custom format), reads the whole archive back, then uploads it to
# s3://$BACKUP_BUCKET/postgres/. Run daily by musicboxd-db-backup.timer. Retention is the bucket's lifecycle
# rule: the host role cannot delete backups (MBD-8), on purpose.
# Exit 0 uploaded; 1 dump, check or upload failed (nothing partial is uploaded); 2 bad config.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
LOCK_FILE=${LOCK_FILE:-/var/lock/musicboxd-deploy.lock}
WORK_DIR=${WORK_DIR:-/var/tmp}

source backup/stack-env.sh      # BUCKET, REGION (validated; exit 2 on bad config)

exec 9>"$LOCK_FILE"; flock 9          # deploy.sh's lock: a deploy never recreates postgres mid-dump

COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)
TMP=$(mktemp -d "$WORK_DIR/musicboxd-backup.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
DUMP=$TMP/db.dump
KEY=postgres/musicboxd-$(date -u +%Y%m%dT%H%M%SZ).dump

# Dump to a local file first: streaming straight to S3 would upload a truncated object if pg_dump died.
"${COMPOSE[@]}" exec -T postgres sh -c 'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$DUMP" \
  || { echo "pg_dump failed; nothing uploaded" >&2; exit 1; }
[[ -s $DUMP ]] || { echo "pg_dump produced an empty file; nothing uploaded" >&2; exit 1; }
# A full read of every data block: `pg_restore -l` reads only the header and TOC, so it passes a truncated file.
"${COMPOSE[@]}" exec -T postgres pg_restore -f /dev/null < "$DUMP" \
  || { echo "Dump is not a readable archive; nothing uploaded" >&2; exit 1; }
exec 9>&-                             # dump verified: release the deploy lock so a slow upload never blocks deploys
aws s3 cp "$DUMP" "s3://$BUCKET/$KEY" --region "$REGION" --only-show-errors \
  || { echo "Upload to s3://$BUCKET/$KEY failed" >&2; exit 1; }

echo "Backup uploaded: s3://$BUCKET/$KEY ($(wc -c < "$DUMP") bytes)"
