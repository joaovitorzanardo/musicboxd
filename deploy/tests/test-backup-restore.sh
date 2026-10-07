#!/usr/bin/env bash
# deploy/tests/test-backup-restore.sh
# End-to-end with real Postgres: starts only the prod compose file's postgres service under a throwaway
# project, seeds awkward data, runs backup-db.sh and restore-check.sh against a fake S3 directory, and checks
# the drill reports MATCH, catches differing data, and refuses an empty database. Needs Docker.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
DEPLOY=$(cd "$HERE/.." && pwd)

TMP=$(mktemp -d)
mkdir -p "$TMP/bin" "$TMP/s3" "$TMP/work"
printf 'DOMAIN=example.test\nGHCR_OWNER=someone\nIMAGE_TAG=latest\nBACKUP_BUCKET=test-bucket\nAWS_REGION=us-east-1\n' > "$TMP/stack.env"
printf 'POSTGRES_DB=musicboxd\nPOSTGRES_USER=musicboxd\nPOSTGRES_PASSWORD=x\n' > "$TMP/postgres.env"
cp "$HERE/fake-aws" "$TMP/bin/aws"
command -v flock >/dev/null 2>&1 || printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/flock"
chmod +x "$TMP/bin/"*

export PATH="$TMP/bin:$PATH" FAKE_S3=$TMP/s3
export COMPOSE_PROJECT_NAME=mbd11test RESTORE_CONTAINER=mbd11test-restore
export MUSICBOXD_ENV_DIR; MUSICBOXD_ENV_DIR=$(cygpath -m "$TMP" 2>/dev/null || echo "$TMP")
export ENV_FILE=$TMP/stack.env PG_ENV_FILE=$TMP/postgres.env LOCK_FILE=$TMP/lock WORK_DIR=$TMP/work

COMPOSE=(docker compose --env-file "$TMP/stack.env" -f "$DEPLOY/docker-compose.prod.yml")
cleanup() {
  "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
  docker rm -f "$RESTORE_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true

src_psql() { "${COMPOSE[@]}" exec -T postgres sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'; }
expect_rc() {  # expect_rc <code> <command...>; output lands in $TMP/out
  local want=$1; shift
  set +e; "$@" > "$TMP/out" 2>&1; local rc=$?; set -e
  [ "$rc" = "$want" ] || { echo "expected rc=$want, got $rc:"; cat "$TMP/out"; exit 1; }
}
no_restore_container() { [ -z "$(docker ps -aq --filter "name=^${RESTORE_CONTAINER}\$")" ]; }

"${COMPOSE[@]}" up -d postgres >/dev/null
for _ in $(seq 60); do
  "${COMPOSE[@]}" exec -T postgres pg_isready -q -h 127.0.0.1 && break
  sleep 1
done
"${COMPOSE[@]}" exec -T postgres pg_isready -q -h 127.0.0.1

# NULLs, newlines, non-ASCII, jsonb, timestamptz, an empty table, two schemas.
src_psql <<'SQL'
CREATE SCHEMA drill;
CREATE TABLE drill.items (id int PRIMARY KEY, note text, at timestamptz NOT NULL, payload jsonb);
INSERT INTO drill.items
SELECT g,
       CASE WHEN g % 50 = 0 THEN NULL ELSE 'línea ' || g || E'\nsegunda linha ✓' END,
       '2026-01-01T00:00:00Z'::timestamptz + g * interval '1 hour',
       jsonb_build_object('g', g, 'tags', jsonb_build_array('a', 'b'))
FROM generate_series(1, 500) g;
CREATE SCHEMA other;
CREATE TABLE other.empty_table (id int);
SQL

# 1 backup lands one readable object in the fake bucket
bash "$DEPLOY/backup/backup-db.sh" > "$TMP/out"
DUMPS=$(ls "$TMP/s3/test-bucket/postgres")
[ "$(wc -l <<<"$DUMPS" | tr -d ' ')" = 1 ]
KEY=postgres/$DUMPS
[ "$(head -c 5 "$TMP/s3/test-bucket/$KEY")" = PGDMP ]
echo "case 1 (real backup) OK"

# 2 drill on the newest dump: MATCH, table summary printed, container removed afterwards
expect_rc 0 bash "$DEPLOY/backup/restore-check.sh"
grep -q '^MATCH' "$TMP/out"
grep -Eq 'drill\.items +500 rows' "$TMP/out"
grep -Eq 'other\.empty_table +0 rows' "$TMP/out"
no_restore_container
echo "case 2 (restore matches) OK"

# 3 explicit key + KEEP=1 leaves the restored container for a manual look
KEEP=1 expect_rc 0 bash "$DEPLOY/backup/restore-check.sh" "$KEY"
NULLS=$(docker exec "$RESTORE_CONTAINER" sh -c 'psql -X -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM drill.items WHERE note IS NULL"')
[ "$NULLS" = 10 ]
docker rm -f "$RESTORE_CONTAINER" >/dev/null
echo "case 3 (KEEP=1) OK"

# 4 live data changed after the dump -> MISMATCH naming the table, exit 1
echo "UPDATE drill.items SET note = 'tampered' WHERE id = 7;" | src_psql
expect_rc 1 bash "$DEPLOY/backup/restore-check.sh"
grep -q '^MISMATCH' "$TMP/out"
grep -q 'drill\.items' "$TMP/out"
no_restore_container
echo "case 4 (mismatch detected) OK"

# 5 keys that are not ours are refused before anything runs
for bad in 'postgres/../etc/passwd' 'other/musicboxd-20261007T043000Z.dump' 'postgres/musicboxd-latest.dump'; do
  expect_rc 2 bash "$DEPLOY/backup/restore-check.sh" "$bad"
done
echo "case 5 (bad keys) OK"

# 6 a dump of a database with no tables cannot "match"
echo "DROP SCHEMA drill CASCADE; DROP SCHEMA other CASCADE;" | src_psql
sleep 1   # next dump gets a later timestamp, so it is the newest
bash "$DEPLOY/backup/backup-db.sh" > /dev/null
expect_rc 2 bash "$DEPLOY/backup/restore-check.sh"
grep -q 'no tables' "$TMP/out"
if grep -q '^MATCH' "$TMP/out"; then echo "empty database reported MATCH"; exit 1; fi   # `! grep` would not trip set -e
echo "case 6 (empty database refused) OK"

# 7 empty bucket -> exit 2
rm -rf "$TMP/s3/test-bucket"
expect_rc 2 bash "$DEPLOY/backup/restore-check.sh"
grep -q 'No dumps' "$TMP/out"
echo "case 7 (no dumps) OK"

echo "backup/restore end-to-end tests OK"
