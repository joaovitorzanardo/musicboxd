# MBD-11 Postgres Backups to S3 and an Exercised Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A systemd timer on the EC2 host dumps the production Postgres to `s3://musicboxd-backups/postgres/` every day, an S3 lifecycle rule bounds retention, and a restore drill script restores a dump into a fresh, network-less Postgres container and proves the data is identical to the source.

**Architecture:** `deploy/backup/backup-db.sh` runs `pg_dump -Fc` inside the running `postgres` container, writes the dump to a host temp file, checks it is a readable archive (`pg_restore -l`), and only then uploads it with the host's AWS CLI (instance-role credentials over IMDSv2; no static keys). It takes the same lock as `deploy.sh`, so a deploy cannot recreate `postgres` in the middle of a dump. Retention is an S3 lifecycle rule, not the script: the host role has no `s3:DeleteObject` on the backup bucket (MBD-8), so a compromised host cannot wipe the backups. `deploy/backup/restore-check.sh` downloads a dump, starts a throwaway `postgres:18` container with `--network none`, runs `pg_restore`, and compares a per-table fingerprint (row count + md5 of all rows) against the live database. Production rollout and the drill are human-run (ticket is `hitl`, `risk: high`).

**Tech Stack:** bash, Docker Compose v2, `postgres:18` (`pg_dump`/`pg_restore`/`psql`), AWS CLI v2 (preinstalled on Amazon Linux 2023), systemd timers, S3 lifecycle + versioning. Tests are bash scripts in `deploy/tests/`, the same style as `test-deploy.sh` (stubbed binaries) and `test-nginx-conf.sh` (real Docker).

**Spec:** `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-postgres-backups-to-s3-and-an-exercised-restore.md` (Jira MBD-11); `ARCHITECTURE-SPINE.md#ad-11` (`_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`); IAM role and bucket name from `docs/runbooks/runbook-aws-host.md` (MBD-8).

## Global Constraints

- Postgres is backed up on a schedule with `pg_dump` to S3, with bounded retention and a restore that has actually been exercised (AD-11).
- Acceptance: a backup file appears in S3 on schedule, and a restore from it into a fresh Postgres actually completes with the same data.
- High-risk check: a person confirms that the restored data matches the source before the ticket closes.
- No static AWS keys anywhere. The host uses the `musicboxd-host-role` instance profile (AD-11).
- Bucket name is `musicboxd-backups`, ARN `arn:aws:s3:::musicboxd-backups` (fixed in MBD-8). The host role allows `s3:PutObject` and `s3:GetObject` on `musicboxd-backups/*` and `s3:ListBucket` on the bucket, and nothing else. **Do not add `s3:DeleteObject`.**
- Postgres is never published on a host port (AD-11). The drill container runs with `--network none`.
- Secrets live in `/etc/musicboxd/*.env` on the host and never in the repo. New settings (`BACKUP_BUCKET`, `AWS_REGION`) go in `stack.env`.
- Region `us-east-1`. Image `postgres:18`, the same as the `postgres` service in `deploy/docker-compose.prod.yml`.
- Scripts live under `deploy/` (CD copies `deploy/` to `/opt/musicboxd`). They are committed with mode `100755` (`git add --chmod=+x`; the repo is developed on Windows). They follow the existing header comment that states the exit-code contract.
- Commit message style: `<Imperative summary> (MBD-11)`. Work on branch `story/mbd-11-postgres-backups`.

## Review Focus

- **A failed or partial dump must never land in S3 looking like a good backup.** Cases: `pg_dump` dies mid-stream, writes 0 bytes, or produces an unreadable archive; or the AWS CLI fails with a non-1 exit code (it uses 2 and 255). Expected: exit 1, nothing uploaded, temp file removed. *Pinned in Task 1, cases 2–5.*
- **The drill must not "pass" without proving anything.** An empty restored database, with no tables to compare, must exit 2 and not print MATCH. Data that differs from the source must exit 1 and print the differing tables. *Pinned in Task 2, cases 4 and 6.*
- **Values with special content must survive the round trip.** This covers NULLs, embedded newlines, non-ASCII text, `jsonb`, `timestamptz` and empty tables. Expected: the fingerprints are identical. *Pinned in Task 2's seed data and case 2.*
- **A deploy that lands during a backup must not recreate `postgres` mid-dump.** The backup takes `deploy.sh`'s lock, so one waits for the other. *Pinned in Task 1, case 7.*
- **The nightly timer can fail silently.** No alarm is in scope for this ticket (AD-11 only requires an alarm for certificate expiry). Mitigations: `Persistent=true`, a failed unit shows in `systemctl --failed`, and the runbook's scheduled-run check (Task 4, step 4) reads the timer, the journal and the newest S3 key. *Pinned in Task 4's verification table. An alarm is listed as a follow-up.*

---

## File Structure

- Create `deploy/backup/backup-db.sh`: dump, check, then upload. Run by the timer.
- Create `deploy/backup/restore-check.sh`: the restore drill (download → fresh container → `pg_restore` → fingerprint compare).
- Create `deploy/systemd/musicboxd-db-backup.service` and `.timer`: daily schedule (same shape as `musicboxd-certbot-renew.*`).
- Create `deploy/aws/backup-bucket-lifecycle.json`: retention rules, applied once with admin credentials.
- Create `deploy/tests/fake-aws`: shared AWS CLI stand-in backed by a directory.
- Create `deploy/tests/test-backup-db.sh`: backup failure paths with stubbed docker (no Docker needed).
- Create `deploy/tests/test-backup-restore.sh`: real-Postgres end-to-end backup → restore → compare.
- Modify `deploy/prod.env.example`: add `BACKUP_BUCKET`, `AWS_REGION`.
- Create `docs/runbooks/runbook-mbd-11-postgres-backups.md`: bucket setup, host install, drill, disaster recovery and results.
- Modify `docs/runbooks/runbook-aws-host.md`: inventory row for the backups bucket.

---

### Task 1: Backup script with a verified dump-then-upload

**Files:**
- Create: `deploy/tests/fake-aws`
- Create: `deploy/tests/test-backup-db.sh`
- Create: `deploy/backup/backup-db.sh`
- Modify: `deploy/prod.env.example`

**Interfaces:**
- Consumes: the `postgres` service in `deploy/docker-compose.prod.yml` (its env has `POSTGRES_USER`, `POSTGRES_DB`), and `/var/lock/musicboxd-deploy.lock` from `deploy/deploy.sh`.
- Produces:
  - `backup-db.sh` with env overrides `ENV_FILE` (default `/etc/musicboxd/stack.env`), `LOCK_FILE` (default `/var/lock/musicboxd-deploy.lock`) and `WORK_DIR` (default `/var/tmp`). It reads `BACKUP_BUCKET` and `AWS_REGION` from `ENV_FILE`.
  - Exit codes: 0 uploaded; 1 failed with nothing uploaded; 2 bad config.
  - Object key format: `postgres/musicboxd-YYYYMMDDTHHMMSSZ.dump` (UTC). Task 2 parses this format.
  - `deploy/tests/fake-aws`: supports `s3 cp` (either direction) and `s3 ls s3://bucket/prefix/`, backed by `$FAKE_S3/<bucket>/<key>`. Touching `$FAKE_S3/.fail_cp` makes `cp` exit 255. Task 2 reuses it.

- [ ] **Step 1: Create the branch**

```bash
git checkout -b story/mbd-11-postgres-backups
```

- [ ] **Step 2: Write the fake AWS CLI**

Create `deploy/tests/fake-aws`:

```bash
#!/usr/bin/env bash
# deploy/tests/fake-aws - stands in for the AWS CLI in tests. Supports `s3 cp` (either direction) and
# `s3 ls s3://bucket/prefix/`, backed by the directory $FAKE_S3. Touch $FAKE_S3/.fail_cp to make cp fail
# the way the real CLI can (exit 255).
set -euo pipefail
args=()
while (($#)); do
  case $1 in
    --region) shift 2 ;;
    --only-show-errors) shift ;;
    *) args+=("$1"); shift ;;
  esac
done
set -- "${args[@]}"
local_path() { echo "$FAKE_S3/${1#s3://}"; }

case "$1 $2" in
  "s3 cp")
    [ -f "$FAKE_S3/.fail_cp" ] && { echo "upload failed: simulated" >&2; exit 255; }
    src=$3; dst=$4
    [[ $src == s3://* ]] && src=$(local_path "$src")
    if [[ $dst == s3://* ]]; then dst=$(local_path "$dst"); mkdir -p "$(dirname "$dst")"; fi
    cp "$src" "$dst" ;;
  "s3 ls")
    dir=$(local_path "$3")
    { [ -d "$dir" ] && [ -n "$(ls -A "$dir")" ]; } || exit 1     # real CLI exits 1 on an empty prefix
    for f in "$dir"/*; do printf '2026-10-07 04:30:00 %10d %s\n' "$(wc -c < "$f")" "$(basename "$f")"; done ;;
  *) echo "fake-aws: unsupported: $*" >&2; exit 64 ;;
esac
```

- [ ] **Step 3: Write the failing test**

Create `deploy/tests/test-backup-db.sh`:

```bash
#!/usr/bin/env bash
# deploy/tests/test-backup-db.sh - exercises deploy/backup/backup-db.sh against stubbed docker and a fake S3.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BACKUP=$HERE/../backup/backup-db.sh

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/s3" "$TMP/work"
export STUB_TMP=$TMP FAKE_S3=$TMP/s3
printf 'DOMAIN=example.test\nBACKUP_BUCKET=test-bucket\nAWS_REGION=us-east-1\n' > "$TMP/stack.env"
: > "$TMP/docker.log"

# Stub docker: `exec ... pg_dump` prints a fake archive (or fails / prints nothing, by flag file);
# `exec ... pg_restore -l` records the bytes it was given and fails if flagged.
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_TMP/docker.log"
case " $* " in
  *pg_dump*)
    [ -f "$STUB_TMP/fail_dump" ] && { printf 'PGDMP partial'; echo "pg_dump: error: connection lost" >&2; exit 1; }
    [ -f "$STUB_TMP/empty_dump" ] && exit 0
    printf 'PGDMP fake archive' ;;
  *pg_restore*)
    cat > "$STUB_TMP/checked.dump"
    [ -f "$STUB_TMP/bad_archive" ] && { echo "pg_restore: error: input file does not appear to be a valid archive" >&2; exit 1; }
    ;;
esac
exit 0
STUB
cp "$HERE/fake-aws" "$TMP/bin/aws"
HAVE_FLOCK=1
if ! command -v flock >/dev/null 2>&1; then
  HAVE_FLOCK=0
  printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/flock"   # no-op so the script runs here
fi
chmod +x "$TMP/bin/"*
export PATH="$TMP/bin:$PATH"

run_backup() { ENV_FILE="${ENV:-$TMP/stack.env}" LOCK_FILE="$TMP/lock" WORK_DIR="$TMP/work" bash "$BACKUP"; }
objects() { find "$TMP/s3" -type f -name '*.dump' | wc -l | tr -d ' '; }
reset() {
  rm -rf "$TMP/s3"/* "$TMP/s3/.fail_cp"
  rm -f "$TMP/fail_dump" "$TMP/empty_dump" "$TMP/bad_archive" "$TMP/checked.dump"
  : > "$TMP/docker.log"
}
expect_rc() {  # expect_rc <code> <command...>; output lands in $TMP/out
  local want=$1; shift
  set +e; "$@" > "$TMP/out" 2>&1; local rc=$?; set -e
  [ "$rc" = "$want" ] || { echo "expected rc=$want, got $rc:"; cat "$TMP/out"; exit 1; }
}
work_empty() { [ -z "$(ls -A "$TMP/work")" ] || { echo "temp files left behind:"; ls -la "$TMP/work"; exit 1; }; }

# 1 happy path: the uploaded object is exactly the archive pg_restore -l checked
reset
expect_rc 0 run_backup
[ "$(objects)" = 1 ]
OBJ=$(find "$TMP/s3" -type f -name '*.dump')
[[ ${OBJ#"$TMP/s3/"} =~ ^test-bucket/postgres/musicboxd-[0-9]{8}T[0-9]{6}Z\.dump$ ]] || { echo "bad key: $OBJ"; exit 1; }
[ "$(cat "$OBJ")" = "PGDMP fake archive" ]
cmp -s "$OBJ" "$TMP/checked.dump"
grep -q 'Backup uploaded: s3://test-bucket/postgres/musicboxd-' "$TMP/out"
grep -q -- '-f docker-compose.prod.yml exec -T postgres' "$TMP/docker.log"
work_empty
echo "case 1 (happy path) OK"

# 2 pg_dump dies mid-stream -> nothing uploaded
reset; touch "$TMP/fail_dump"
expect_rc 1 run_backup
[ "$(objects)" = 0 ]; grep -q 'pg_dump failed' "$TMP/out"; work_empty
echo "case 2 (pg_dump fails) OK"

# 3 pg_dump writes nothing -> nothing uploaded
reset; touch "$TMP/empty_dump"
expect_rc 1 run_backup
[ "$(objects)" = 0 ]; grep -q 'empty' "$TMP/out"; work_empty
echo "case 3 (empty dump) OK"

# 4 unreadable archive -> nothing uploaded
reset; touch "$TMP/bad_archive"
expect_rc 1 run_backup
[ "$(objects)" = 0 ]; grep -q 'not a readable archive' "$TMP/out"; work_empty
echo "case 4 (bad archive) OK"

# 5 upload fails with the CLI's exit 255 -> script still exits 1
reset; touch "$TMP/s3/.fail_cp"
expect_rc 1 run_backup
grep -q 'Upload to s3://test-bucket/postgres/musicboxd-.* failed' "$TMP/out"; work_empty
echo "case 5 (upload fails) OK"

# 6 bad config -> exit 2 before touching docker
for cfg in 'AWS_REGION=us-east-1' 'BACKUP_BUCKET=test-bucket' $'BACKUP_BUCKET=Bad_Bucket!\nAWS_REGION=us-east-1'; do
  reset; printf 'DOMAIN=example.test\n%s\n' "$cfg" > "$TMP/bad.env"
  ENV=$TMP/bad.env expect_rc 2 run_backup
  [ ! -s "$TMP/docker.log" ] || { echo "docker was called with bad config"; exit 1; }
done
echo "case 6 (bad config) OK"

# 7 shares deploy.sh's lock: waits while a deploy holds it
if [ "$HAVE_FLOCK" = 1 ]; then
  reset; : > "$TMP/lock"
  flock "$TMP/lock" sleep 2 &
  sleep 0.5
  start=$SECONDS
  expect_rc 0 run_backup
  wait
  [ $((SECONDS - start)) -ge 1 ] || { echo "backup did not wait for the deploy lock"; exit 1; }
  echo "case 7 (lock wait) OK"
else
  echo "case 7 SKIPPED: flock not available on this host"
fi
echo "backup script tests OK"
```

- [ ] **Step 4: Run the test and verify that it fails**

Run: `bash deploy/tests/test-backup-db.sh`
Expected: FAIL in case 1 (`bash: .../backup/backup-db.sh: No such file or directory`, rc 127 ≠ 0).

- [ ] **Step 5: Write the backup script**

Create `deploy/backup/backup-db.sh`:

```bash
#!/usr/bin/env bash
# Dumps the production database (pg_dump custom format), checks the archive is readable, then uploads it to
# s3://$BACKUP_BUCKET/postgres/. Run daily by musicboxd-db-backup.timer. Retention is the bucket's lifecycle
# rule: the host role cannot delete backups (MBD-8), on purpose.
# Exit 0 uploaded; 1 dump, check or upload failed (nothing partial is uploaded); 2 bad config.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
LOCK_FILE=${LOCK_FILE:-/var/lock/musicboxd-deploy.lock}
WORK_DIR=${WORK_DIR:-/var/tmp}

env_get() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -1; }
BUCKET=$(env_get BACKUP_BUCKET)
REGION=$(env_get AWS_REGION)
[[ -n $BUCKET && -n $REGION ]] || { echo "BACKUP_BUCKET and AWS_REGION must be set in $ENV_FILE" >&2; exit 2; }
[[ $BUCKET =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || { echo "Invalid BACKUP_BUCKET '$BUCKET'" >&2; exit 2; }

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
"${COMPOSE[@]}" exec -T postgres pg_restore -l < "$DUMP" > /dev/null \
  || { echo "Dump is not a readable archive; nothing uploaded" >&2; exit 1; }
aws s3 cp "$DUMP" "s3://$BUCKET/$KEY" --region "$REGION" --only-show-errors \
  || { echo "Upload to s3://$BUCKET/$KEY failed" >&2; exit 1; }

echo "Backup uploaded: s3://$BUCKET/$KEY ($(wc -c < "$DUMP") bytes)"
```

- [ ] **Step 6: Run the test and verify that it passes**

Run: `bash deploy/tests/test-backup-db.sh`
Expected: `case 1` through `case 6 ... OK` (case 7 OK on Linux, SKIPPED on Git Bash), then `backup script tests OK`.

- [ ] **Step 7: Document the new settings**

In `deploy/prod.env.example`, replace the line `CERTBOT_EMAIL=you@example.com` with:

```
CERTBOT_EMAIL=you@example.com
BACKUP_BUCKET=musicboxd-backups    # MBD-11; the suffixed name if musicboxd-backups was taken (see runbook-aws-host.md)
AWS_REGION=us-east-1
```

Then confirm that the compose config test still passes. The new keys are only interpolation input.
Run: `bash deploy/tests/test-compose-config.sh`
Expected: `prod compose config OK`

- [ ] **Step 8: Commit (scripts executable)**

```bash
git add --chmod=+x deploy/backup/backup-db.sh deploy/tests/test-backup-db.sh deploy/tests/fake-aws
git add deploy/prod.env.example
git ls-files -s deploy/backup deploy/tests/fake-aws deploy/tests/test-backup-db.sh   # expect 100755 on all three
git commit -m "Add pg_dump-to-S3 backup script with verify-before-upload (MBD-11)"
```

---

### Task 2: Restore drill script with fingerprint comparison

**Files:**
- Create: `deploy/tests/test-backup-restore.sh`
- Create: `deploy/backup/restore-check.sh`

**Interfaces:**
- Consumes:
  - `deploy/backup/backup-db.sh` and its key format `postgres/musicboxd-YYYYMMDDTHHMMSSZ.dump` (Task 1).
  - `deploy/tests/fake-aws` (Task 1).
  - The `postgres` service in `docker-compose.prod.yml`.
- Produces:
  - `restore-check.sh [s3-key]`. With no key, it uses the newest dump.
  - Env overrides: `ENV_FILE`, `PG_ENV_FILE` (default `/etc/musicboxd/postgres.env`), `RESTORE_IMAGE` (default `postgres:18`), `RESTORE_CONTAINER` (default `musicboxd-restore-check`), `READY_TIMEOUT` (default 90), `WORK_DIR`, `COMPARE` (default 1; 0 skips the comparison with the live DB), and `KEEP` (default 0; 1 leaves the restored container running).
  - Prints `MATCH: ...` on success and `MISMATCH: ...` plus a diff on stderr.
  - Exit codes: 0 restored and identical; 1 restore failed or data differs; 2 bad input or nothing to compare. Task 3's runbook relies on these strings and codes.

- [ ] **Step 1: Write the failing end-to-end test**

Create `deploy/tests/test-backup-restore.sh`:

```bash
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
```

- [ ] **Step 2: Run the test and verify that it fails**

Run: `bash deploy/tests/test-backup-restore.sh`
Expected: `case 1 (real backup) OK`, then FAIL in case 2 (`expected rc=0, got 127` with `restore-check.sh: No such file or directory`).

- [ ] **Step 3: Write the restore drill script**

Create `deploy/backup/restore-check.sh`:

```bash
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
```

- [ ] **Step 4: Run the test and verify that it passes**

Run: `bash deploy/tests/test-backup-restore.sh`
Expected: `case 1` through `case 7 ... OK`, then `backup/restore end-to-end tests OK`.

If case 2 fails inside `pg_restore` with an error about schema `public`, check the dump with `pg_restore -l`. In PG 18, `pg_dump` does not emit `CREATE SCHEMA public`, so a fresh database should restore cleanly. Only if the archive does list it, add `--clean --if-exists` to the `pg_restore` call and re-run. Do not drop `--exit-on-error`.

- [ ] **Step 5: Re-run the Task 1 tests (no regression)**

Run: `bash deploy/tests/test-backup-db.sh`
Expected: `backup script tests OK`

- [ ] **Step 6: Commit**

```bash
git add --chmod=+x deploy/backup/restore-check.sh deploy/tests/test-backup-restore.sh
git ls-files -s deploy/backup   # expect 100755 on both scripts
git commit -m "Add restore drill that verifies a dump against the live database (MBD-11)"
```

---

### Task 3: Schedule, retention rule and runbook

**Files:**
- Create: `deploy/systemd/musicboxd-db-backup.service`
- Create: `deploy/systemd/musicboxd-db-backup.timer`
- Create: `deploy/aws/backup-bucket-lifecycle.json`
- Create: `docs/runbooks/runbook-mbd-11-postgres-backups.md`
- Modify: `docs/runbooks/runbook-aws-host.md` (inventory row for the backups bucket)

**Interfaces:**
- Consumes: `/opt/musicboxd/backup/backup-db.sh` and `/opt/musicboxd/backup/restore-check.sh` (Tasks 1–2, copied to the host by `deploy.sh`), and their exit codes and the `MATCH`/`MISMATCH` strings.
- Produces: the `musicboxd-db-backup.timer` and `.service` unit names, the lifecycle rule IDs `expire-postgres-dumps` and `remove-expired-delete-markers`, and the runbook Task 4 follows.

- [ ] **Step 1: Write the systemd units**

Create `deploy/systemd/musicboxd-db-backup.service`:

```ini
# deploy/systemd/musicboxd-db-backup.service
[Unit]
Description=Back up the musicboxd Postgres database to S3
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=/opt/musicboxd
ExecStart=/opt/musicboxd/backup/backup-db.sh
```

Create `deploy/systemd/musicboxd-db-backup.timer`:

```ini
# deploy/systemd/musicboxd-db-backup.timer
[Unit]
Description=Daily musicboxd Postgres backup to S3

[Timer]
OnCalendar=*-*-* 04:30:00 UTC
RandomizedDelaySec=15m
Persistent=true

[Install]
WantedBy=timers.target
```

- [ ] **Step 2: Write the lifecycle rule**

Create `deploy/aws/backup-bucket-lifecycle.json`:

```json
{
  "Rules": [
    {
      "ID": "expire-postgres-dumps",
      "Filter": { "Prefix": "postgres/" },
      "Status": "Enabled",
      "Expiration": { "Days": 30 },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 7 },
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
    },
    {
      "ID": "remove-expired-delete-markers",
      "Filter": { "Prefix": "postgres/" },
      "Status": "Enabled",
      "Expiration": { "ExpiredObjectDeleteMarker": true }
    }
  ]
}
```

With versioning on, a dump is current for 30 days and noncurrent for 7 more, so nothing lives past about 37 days. An object overwritten by a compromised host stays recoverable for 7 days.

- [ ] **Step 3: Write the runbook**

Create `docs/runbooks/runbook-mbd-11-postgres-backups.md`:

````markdown
# Runbook: MBD-11 Postgres backups to S3 and the restore drill

Daily `pg_dump` of the production database to S3, retention by an S3 lifecycle rule, and a restore drill
that proves a dump restores into a fresh Postgres with identical data. Human-run (`hitl`, `risk: high`).

| Item | Value |
| ---- | ----- |
| Bucket | `musicboxd-backups` (or the suffixed name recorded in `runbook-aws-host.md`), region `us-east-1` |
| Key layout | `postgres/musicboxd-YYYYMMDDTHHMMSSZ.dump` (UTC, `pg_dump -Fc`) |
| Schedule | `musicboxd-db-backup.timer`, daily 04:30 UTC + up to 15 min random delay, catches up after downtime |
| Retention | lifecycle: current 30 days, noncurrent 7 days, incomplete uploads 1 day (`deploy/aws/backup-bucket-lifecycle.json`) |
| Access | host role `musicboxd-host-role`: Put/Get objects + List bucket, **no delete** (MBD-8) |
| Encryption | SSE-S3 (bucket default); public access fully blocked |
| Scripts on host | `/opt/musicboxd/backup/backup-db.sh`, `/opt/musicboxd/backup/restore-check.sh` (copied by CD) |

Dumps will contain user data (emails, password hashes) once those features ship. Nobody but the host
role and the account admin may read this bucket.

## 1. Create the bucket (admin credentials, from your machine, at the repo root)

```bash
aws s3api create-bucket --bucket musicboxd-backups --region us-east-1
aws s3api put-public-access-block --bucket musicboxd-backups \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-versioning --bucket musicboxd-backups --versioning-configuration Status=Enabled
aws s3api put-bucket-lifecycle-configuration --bucket musicboxd-backups \
  --lifecycle-configuration file://deploy/aws/backup-bucket-lifecycle.json
```

If `create-bucket` says `BucketAlreadyExists`, use `musicboxd-backups-<suffix>` everywhere below, update the
`musicboxd-s3` inline policy's two backup ARNs (runbook-aws-host.md section 2), and record the name in its inventory.

Read it all back:

```bash
aws s3api get-public-access-block --bucket musicboxd-backups      # all four true
aws s3api get-bucket-versioning --bucket musicboxd-backups        # "Status": "Enabled"
aws s3api get-bucket-encryption --bucket musicboxd-backups        # SSEAlgorithm AES256
aws s3api get-bucket-lifecycle-configuration --bucket musicboxd-backups   # the two rules
```

## 2. Prepare the host (SSM session)

```bash
aws ssm start-session --target <instance-id> --region us-east-1
cd /opt/musicboxd
aws --version                                    # AWS CLI v2 ships with Amazon Linux 2023
aws sts get-caller-identity                      # Arn must be .../assumed-role/musicboxd-host-role/...
ls -l backup/                                    # both scripts present and executable (-rwxr-xr-x), i.e. CD ran after the merge
sudoedit /etc/musicboxd/stack.env                # add: BACKUP_BUCKET=musicboxd-backups  and  AWS_REGION=us-east-1
```

## 3. Install the timer

```bash
sudo cp /opt/musicboxd/systemd/musicboxd-db-backup.* /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/musicboxd-db-backup.service   # no output = OK
sudo systemctl daemon-reload
sudo systemctl enable --now musicboxd-db-backup.timer
systemctl list-timers musicboxd-db-backup.timer   # NEXT must be in the future
```

## 4. First backup by hand

```bash
sudo systemctl start musicboxd-db-backup.service
systemctl status musicboxd-db-backup.service --no-pager                   # status=0/SUCCESS
sudo journalctl -u musicboxd-db-backup.service --no-pager | tail -3        # "Backup uploaded: s3://.../postgres/musicboxd-...dump (N bytes)"
aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1
```

## 5. Restore drill (acceptance criterion + high-risk check)

The production database has no application tables yet, and an empty restore proves nothing
(`restore-check.sh` refuses it with exit 2). So first seed a canary with a random token, then back up and
restore. Run everything in one session, without deploys in between, so live and restored data are the same.

```bash
cd /opt/musicboxd
TOKEN=$(openssl rand -hex 8); echo "canary token: $TOKEN"     # write it down
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<SQL
CREATE SCHEMA ops_restore_drill;
CREATE TABLE ops_restore_drill.canary (id int PRIMARY KEY, token text NOT NULL, note text, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO ops_restore_drill.canary (id, token, note)
SELECT g, '$TOKEN', CASE WHEN g % 100 = 0 THEN NULL ELSE 'row ' || g || E'\nwith a newline and ünïcode' END
FROM generate_series(1, 1000) g;
SQL

sudo systemctl start musicboxd-db-backup.service
sudo journalctl -u musicboxd-db-backup.service --no-pager | tail -1     # note the key

sudo KEEP=1 ./backup/restore-check.sh                 # expect: "ops_restore_drill.canary  1000 rows" and "MATCH: ..."; exit 0
echo "exit=$?"
```

**Person checks (high-risk gate).** Look at the restored copy yourself and compare it with the live database:

```bash
Q='SELECT count(*), count(DISTINCT token), min(token), count(*) FILTER (WHERE note IS NULL) FROM ops_restore_drill.canary'
sudo docker exec musicboxd-restore-check sh -c "psql -X -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$Q\""
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c "psql -X -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$Q\""
# both: 1000 | 1 | <your token> | 10
```

Clean up:

```bash
sudo docker rm -f musicboxd-restore-check
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DROP SCHEMA ops_restore_drill CASCADE"'
```

(The drill dump keeps the canary schema until it expires. That is harmless.)

## 6. Scheduled run (the next day)

```bash
systemctl list-timers musicboxd-db-backup.timer                       # LAST is today ~04:30-04:45 UTC
sudo journalctl -u musicboxd-db-backup.service --since today --no-pager | tail -2
aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1         # a key stamped in today's 04:30-04:45 window
```

## 7. The host cannot delete backups

```bash
aws s3 rm s3://musicboxd-backups/postgres/<a key from above> --region us-east-1   # expect: AccessDenied
```

## Verification results

| Check | Expected | Result | Date |
| ----- | -------- | ------ | ---- |
| V1 bucket settings (step 1 read-back) | public access blocked, versioning on, AES256, two lifecycle rules | `TODO` | |
| V2 manual backup (step 4) | object in `postgres/`, unit SUCCESS | `TODO` | |
| V3 restore drill (step 5) | `MATCH`, exit 0 | `TODO` | |
| V4 person check (step 5) | both queries `1000 \| 1 \| <token> \| 10`; checked by: `TODO name` | `TODO` | |
| V5 scheduled run (step 6) | object from the timer window, no manual start | `TODO` | |
| V6 no delete (step 7) | AccessDenied | `TODO` | |

## Day-to-day checks

- Is the backup running? `systemctl list-timers musicboxd-db-backup.timer` and `systemctl --failed`.
  A failed run leaves the unit in `failed` state until the next success. Newest object:
  `aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1 | tail -1`.
- **No alarm fires on a failed backup yet.** Follow-up: alert when the newest object is older than 26h.
- Re-run the drill after any Postgres major-version change (`postgres:18` → 19). `RESTORE_IMAGE` must match
  the service image, and `restore-check.sh`'s default must be bumped with it.
- Once real users write data, a live comparison shows writes made after the dump as `MISMATCH`. For a drill
  on a busy database, run `sudo COMPARE=0 KEEP=1 ./backup/restore-check.sh` and inspect by hand.

## Disaster recovery: restore into production

Not exercised against production. It uses the same `pg_restore` invocation the drill exercises.

```bash
cd /opt/musicboxd
C=(sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml)
sudo systemctl start musicboxd-db-backup.service || true     # snapshot the current state first, if it is reachable
aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1 # pick KEY
aws s3 cp s3://musicboxd-backups/postgres/<KEY> /var/tmp/restore.dump --region us-east-1
"${C[@]}" stop api                                            # nginx keeps serving the SPA; API returns 502
"${C[@]}" exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
"${C[@]}" exec -T postgres sh -c 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < /var/tmp/restore.dump
"${C[@]}" up -d api
sudo rm -f /var/tmp/restore.dump
```

To restore an object the lifecycle has already expired as a "current" version (within its 7 noncurrent
days), use an admin session: `aws s3api list-object-versions --bucket musicboxd-backups --prefix postgres/`,
then `aws s3api get-object --version-id ...`.

## Cost

Some dumps of a small database (~30 current + ~7 noncurrent) cost well under $0.10/month in S3 Standard.
````

- [ ] **Step 4: Point the host inventory at the new bucket**

In `docs/runbooks/runbook-aws-host.md`, replace:

```
| Backups bucket (not created here) | `musicboxd-backups` (fallback if name is taken: `TODO musicboxd-backups-<suffix>`) |
```

with:

```
| Backups bucket (MBD-11)           | `musicboxd-backups` (fallback if name is taken: `TODO musicboxd-backups-<suffix>`); setup in `runbook-mbd-11-postgres-backups.md` |
```

and in the MBD-9 "Not in this ticket" bullet, change `Postgres backups (MBD-11)` to `Postgres backups (MBD-11, see runbook-mbd-11-postgres-backups.md)`.

- [ ] **Step 5: Check the JSON and run the whole deploy test suite**

Run:

```bash
docker run --rm -i python:3-alpine python -c "import json,sys; r=json.load(sys.stdin)['Rules']; assert [x['ID'] for x in r]==['expire-postgres-dumps','remove-expired-delete-markers']; print('lifecycle JSON OK')" < deploy/aws/backup-bucket-lifecycle.json
for t in deploy/tests/test-*.sh; do echo "== $t"; bash "$t" || exit 1; done
```

Expected: `lifecycle JSON OK`, then every test prints its final `... OK` line. These include `backup script tests OK` and `backup/restore end-to-end tests OK`.

- [ ] **Step 6: Commit**

```bash
git add deploy/systemd/musicboxd-db-backup.service deploy/systemd/musicboxd-db-backup.timer \
  deploy/aws/backup-bucket-lifecycle.json docs/runbooks/runbook-mbd-11-postgres-backups.md docs/runbooks/runbook-aws-host.md
git commit -m "Add daily backup timer, retention lifecycle and backup runbook (MBD-11)"
```

- [ ] **Step 7: Open the PR**

Push `story/mbd-11-postgres-backups` and open a PR to `main` that links Jira MBD-11. The PR body states that production setup and the drill (Task 4) are human-run after the merge, because CD is what copies `deploy/backup/` to the host.

---

### Task 4: Production rollout and the exercised restore (human-run, after merge)

The ticket is `hitl` and `risk: high`, and the agent has no production access. A person runs
`docs/runbooks/runbook-mbd-11-postgres-backups.md` against the real account and host.

**Files:**
- Modify: `docs/runbooks/runbook-mbd-11-postgres-backups.md` (fill in the Verification results table)

**Interfaces:**
- Consumes: everything from Tasks 1–3, merged to `main` and deployed by CD.
- Produces: the filled-in verification table. It is the evidence for closing MBD-11.

- [ ] **Step 1: Confirm that CD deployed the merge.** On the host, run `ls -l /opt/musicboxd/backup/`. Both scripts must be present and executable.
- [ ] **Step 2: Runbook sections 1–4.** Create the bucket, prepare the host, install the timer and take the first backup. Record V1 and V2.
- [ ] **Step 3: Runbook section 5 (restore drill).** `restore-check.sh` prints `MATCH` and exits 0. A person runs both canary queries, confirms they are identical, and records their name and the result under V3/V4. Clean up the container and the canary schema.
- [ ] **Step 4: Runbook section 6, the day after.** The timer produced an object in its window without a manual start. Record V5.
- [ ] **Step 5: Runbook section 7.** Record V6 (AccessDenied).
- [ ] **Step 6: Commit the results and close the ticket.**

```bash
git checkout main && git pull
git checkout -b story/mbd-11-backup-verification
git add docs/runbooks/runbook-mbd-11-postgres-backups.md
git commit -m "Record MBD-11 backup and restore drill results (MBD-11)"
```

Open the PR. Then, in Jira, comment the V3/V4 evidence on MBD-11 and move it to Done.
