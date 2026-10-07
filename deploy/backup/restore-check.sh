#!/usr/bin/env bash
# Restore drill. Downloads a dump from S3 (the newest one unless a key is given), restores it into a
# throwaway Postgres container with no network, and compares every table's row count and content hash with
# the live database. Writes to the live database after the dump show up as differences, so run it right
# after a backup (or set COMPARE=0). KEEP=1 leaves the restored container running for a manual look.
# Exit 0 restored and identical; 1 restore failed or data differs; 2 bad input or nothing to compare.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
PG_ENV_FILE=${PG_ENV_FILE:-/etc/musicboxd/postgres.env}
RESTORE_IMAGE=${RESTORE_IMAGE:-postgres:18}       # keep in step with the postgres service image
RESTORE_CONTAINER=${RESTORE_CONTAINER:-musicboxd-restore-check}
READY_TIMEOUT=${READY_TIMEOUT:-90}
WORK_DIR=${WORK_DIR:-/var/tmp}
COMPARE=${COMPARE:-1}
KEEP=${KEEP:-0}
KEY=${1:-}

env_get() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -1; }
BUCKET=$(env_get BACKUP_BUCKET)
REGION=$(env_get AWS_REGION)
[[ -n $BUCKET && -n $REGION ]] || { echo "BACKUP_BUCKET and AWS_REGION must be set in $ENV_FILE" >&2; exit 2; }
[[ -r $PG_ENV_FILE ]] || { echo "Cannot read $PG_ENV_FILE" >&2; exit 2; }

if [[ -z $KEY ]]; then
  NEWEST=$(aws s3 ls "s3://$BUCKET/postgres/" --region "$REGION" | awk '{print $4}' \
    | grep -E '^musicboxd-[0-9]{8}T[0-9]{6}Z\.dump$' | sort | tail -1 || true)
  [[ -n $NEWEST ]] || { echo "No dumps under s3://$BUCKET/postgres/" >&2; exit 2; }
  KEY=postgres/$NEWEST
fi
[[ $KEY =~ ^postgres/musicboxd-[0-9]{8}T[0-9]{6}Z\.dump$ ]] \
  || { echo "Refusing key '$KEY' (want postgres/musicboxd-<UTC stamp>.dump)" >&2; exit 2; }

COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)
TMP=$(mktemp -d "$WORK_DIR/musicboxd-restore.XXXXXX")
cleanup() {
  rm -rf "$TMP"
  if [[ $KEEP != 1 ]]; then docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# One line per table: "<schema>.<table>|<row count>|<md5 of all rows in text order>".
# Sequences, views and roles are not compared.
FINGERPRINT_SQL=$(cat <<'SQL'
SELECT format(
  'SELECT %L || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(t::text, E''\n'' ORDER BY t::text)), ''-'') FROM %I.%I t',
  n.nspname || '.' || c.relname, n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind = 'r'
  AND n.nspname <> 'information_schema'
  AND n.nspname NOT LIKE 'pg\_%'
ORDER BY 1
\gexec
SQL
)
fingerprint() {  # $@ = command prefix that runs a shell in the target postgres container with stdin attached
  "$@" sh -c 'exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<<"$FINGERPRINT_SQL" | sort
}

echo "Downloading s3://$BUCKET/$KEY"
aws s3 cp "s3://$BUCKET/$KEY" "$TMP/db.dump" --region "$REGION" --only-show-errors \
  || { echo "Download failed" >&2; exit 1; }

docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$RESTORE_CONTAINER" --network none --env-file "$PG_ENV_FILE" "$RESTORE_IMAGE" >/dev/null

# The image's first-start init runs a socket-only server and then restarts it; TCP on 127.0.0.1 answers
# only once the final server is up, so this cannot race the init restart.
end=$((SECONDS + READY_TIMEOUT))
until docker exec "$RESTORE_CONTAINER" pg_isready -q -h 127.0.0.1; do
  if (( SECONDS >= end )); then
    echo "Restore container not ready after ${READY_TIMEOUT}s" >&2
    docker logs --tail 30 "$RESTORE_CONTAINER" >&2 || true
    exit 1
  fi
  sleep 1
done

docker exec -i "$RESTORE_CONTAINER" \
  sh -c 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$TMP/db.dump" \
  || { echo "pg_restore failed" >&2; exit 1; }

RESTORED=$(fingerprint docker exec -i "$RESTORE_CONTAINER")
[[ -n $RESTORED ]] || { echo "Restored database has no tables; nothing to compare" >&2; exit 2; }
echo "Restored $KEY into $RESTORE_CONTAINER: $(wc -l <<<"$RESTORED" | tr -d ' ') tables"
awk -F'|' '{printf "  %-50s %s rows\n", $1, $2}' <<<"$RESTORED"

if [[ $COMPARE == 1 ]]; then
  SOURCE=$(fingerprint "${COMPOSE[@]}" exec -T postgres)
  if diff <(echo "$SOURCE") <(echo "$RESTORED") >&2; then
    echo "MATCH: restored data is identical to the live database"
  else
    echo "MISMATCH: restored data differs from the live database (diff above: < live, > restored)" >&2
    exit 1
  fi
fi
if [[ $KEEP == 1 ]]; then
  echo "Kept container $RESTORE_CONTAINER; remove it with: docker rm -f $RESTORE_CONTAINER"
fi
