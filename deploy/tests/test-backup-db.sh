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
# `exec ... pg_restore` records the bytes it was given and fails if flagged. A truncated archive (header
# intact) still passes `pg_restore -l` and fails only a full read.
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
    case " $* " in
      *" -l "*) ;;
      *) [ -f "$STUB_TMP/truncated_archive" ] && { echo "pg_restore: error: could not read from input file: end of file" >&2; exit 1; } ;;
    esac
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
  rm -f "$TMP/fail_dump" "$TMP/empty_dump" "$TMP/bad_archive" "$TMP/truncated_archive" "$TMP/checked.dump" "$TMP/lock_held_during_upload"
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
for cfg in 'AWS_REGION=us-east-1' 'BACKUP_BUCKET=test-bucket' $'BACKUP_BUCKET=Bad_Bucket!\nAWS_REGION=us-east-1' \
           $'BACKUP_BUCKET=test-bucket\nAWS_REGION=us east 1'; do
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

# 8 inline comments and trailing spaces in stack.env are ignored (dotenv style, as prod.env.example writes them)
reset
printf 'DOMAIN=example.test\nBACKUP_BUCKET=test-bucket    # MBD-11 note\nAWS_REGION=us-east-1  # region \n' > "$TMP/commented.env"
ENV=$TMP/commented.env expect_rc 0 run_backup
[ -n "$(find "$TMP/s3/test-bucket/postgres" -name '*.dump' 2>/dev/null)" ] \
  || { echo "commented env did not upload to test-bucket"; cat "$TMP/out"; exit 1; }
echo "case 8 (inline comments) OK"

# 9 truncated archive whose header still lists fine -> nothing uploaded
reset; touch "$TMP/truncated_archive"
expect_rc 1 run_backup
[ "$(objects)" = 0 ]; grep -q 'not a readable archive' "$TMP/out"; work_empty
echo "case 9 (truncated archive) OK"

# 10 the deploy lock is released before the upload, so a slow or hung upload never blocks deploys
if [ "$HAVE_FLOCK" = 1 ]; then
  reset; mv "$TMP/bin/aws" "$TMP/bin/aws-real"
  cat > "$TMP/bin/aws" <<'STUB'
#!/usr/bin/env bash
flock -n "$STUB_TMP/lock" true || touch "$STUB_TMP/lock_held_during_upload"
exec "$STUB_TMP/bin/aws-real" "$@"
STUB
  chmod +x "$TMP/bin/aws"
  expect_rc 0 run_backup
  mv "$TMP/bin/aws-real" "$TMP/bin/aws"
  [ ! -f "$TMP/lock_held_during_upload" ] || { echo "deploy lock still held during upload"; exit 1; }
  echo "case 10 (lock released before upload) OK"
else
  echo "case 10 SKIPPED: flock not available on this host"
fi
echo "backup script tests OK"
