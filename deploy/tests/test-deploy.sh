#!/usr/bin/env bash
# deploy/tests/test-deploy.sh - exercises deploy/deploy.sh against stubbed docker/git.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
DEPLOY=$HERE/../deploy.sh

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/src/deploy" "$TMP/stack"
export STUB_TMP=$TMP
echo "services: {}" > "$TMP/src/deploy/docker-compose.prod.yml"
printf 'DOMAIN=example.test\nIMAGE_TAG=sha-old\n' > "$TMP/stack.env"
echo healthy > "$TMP/health"; echo running > "$TMP/web_state"; : > "$TMP/docker.log"

# Stub docker: logs args; `ps` prints "<service> <state> <health>" lines (health empty when none).
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_TMP/docker.log"
case " $* " in
  *" up "*)
    cur=$(sed -n 's/^IMAGE_TAG=//p' "$STUB_TMP/stack.env" | tail -1)
    if [ -f "$STUB_TMP/fail_up_tag" ] && [ "$cur" = "$(cat "$STUB_TMP/fail_up_tag")" ]; then exit 1; fi ;;
  *" ps "*)
    cur=$(sed -n 's/^IMAGE_TAG=//p' "$STUB_TMP/stack.env" | tail -1)
    if [ -f "$STUB_TMP/bad_tag" ] && [ "$cur" = "$(cat "$STUB_TMP/bad_tag")" ]; then h=unhealthy; else h=$(cat "$STUB_TMP/health"); fi
    echo "api running $h"
    echo "web $(cat "$STUB_TMP/web_state") "
    echo "db running healthy" ;;
esac
exit 0
STUB
cat > "$TMP/bin/git" <<'STUB'
#!/usr/bin/env bash
echo "git $*" >> "$STUB_TMP/docker.log"
exit 0
STUB
source "$HERE/lib.sh"
stub_flock_if_missing
chmod +x "$TMP/bin/"*
export PATH="$TMP/bin:$PATH"

run_deploy() {
  ENV_FILE="$TMP/stack.env" SRC_DIR="$TMP/src" STACK_DIR="$TMP/stack" \
    LOCK_FILE="$TMP/lock" HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-3} bash "$DEPLOY" "$@"
}

TAG=sha-$(printf 'a%.0s' {1..40})
TAG_B=sha-$(printf 'b%.0s' {1..40})

# 1 happy path
run_deploy "$TAG"
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
grep -q 'compose.* pull' "$TMP/docker.log"
grep -q 'compose.* up -d' "$TMP/docker.log"
[ -f "$TMP/stack/docker-compose.prod.yml" ]
echo "case 1 (happy path) OK"

# 2 api unhealthy -> rollback, exit 1
echo unhealthy > "$TMP/health"; : > "$TMP/docker.log"
if run_deploy "$TAG_B"; then echo "expected failure (unhealthy)"; exit 1; fi
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
[ "$(grep -c 'compose.* up -d' "$TMP/docker.log")" = 2 ]
echo "case 2 (unhealthy -> rollback) OK"

# 2b a non-running service also fails the deploy even if api is healthy
echo healthy > "$TMP/health"; echo restarting > "$TMP/web_state"; : > "$TMP/docker.log"
if run_deploy "$TAG_B"; then echo "expected failure (web restarting)"; exit 1; fi
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
echo running > "$TMP/web_state"
echo "case 2b (service not running -> rollback) OK"

# 2c rollback succeeds: only the new tag is unhealthy
echo healthy > "$TMP/health"; echo "$TAG_B" > "$TMP/bad_tag"; : > "$TMP/docker.log"
set +e; run_deploy "$TAG_B" 2> "$TMP/err"; rc=$?; set -e
[ "$rc" = 1 ]
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
[ "$(grep -c 'compose.* up -d' "$TMP/docker.log")" = 2 ]
! grep -q 'Rollback to .* also failed' "$TMP/err"
rm -f "$TMP/bad_tag"
echo "case 2c (rollback healthy) OK"

# 2d up -d itself fails for the new tag -> exit 1, tag restored
echo "$TAG_B" > "$TMP/fail_up_tag"; : > "$TMP/docker.log"
set +e; run_deploy "$TAG_B" 2> "$TMP/err"; rc=$?; set -e
[ "$rc" = 1 ]
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
! grep -q 'Rollback to .* also failed' "$TMP/err"
rm -f "$TMP/fail_up_tag"
echo "case 2d (up -d fails -> rollback) OK"

# 3 bad tags exit 2 and leave stack.env untouched
: > "$TMP/docker.log"
for bad in "" latest 'sha-x; rm -rf /' "sha-${TAG#sha-}0"; do
  set +e; run_deploy "$bad" 2>/dev/null; rc=$?; set -e
  [ "$rc" = 2 ] || { echo "bad tag '$bad' gave rc=$rc"; exit 1; }
done
set +e; run_deploy 2>/dev/null; rc=$?; set -e; [ "$rc" = 2 ]
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
[ ! -s "$TMP/docker.log" ]
echo "case 3 (bad tags) OK"

# 5 stack.env without IMAGE_TAG: tag gets appended; failure has nothing to roll back to
cp "$TMP/stack.env" "$TMP/stack.env.keep"
printf 'DOMAIN=example.test\n' > "$TMP/stack.env"
run_deploy "$TAG"
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
printf 'DOMAIN=example.test\n' > "$TMP/stack.env"; echo "$TAG_B" > "$TMP/bad_tag"; : > "$TMP/docker.log"
set +e; run_deploy "$TAG_B" 2>/dev/null; rc=$?; set -e
[ "$rc" = 1 ]
[ "$(grep -c 'compose.* up -d' "$TMP/docker.log")" = 1 ]
rm -f "$TMP/bad_tag"; cp "$TMP/stack.env.keep" "$TMP/stack.env"
echo "case 5 (no IMAGE_TAG in env file) OK"

# 4 lock held -> deploy waits for it
if [ "$HAVE_FLOCK" = 1 ]; then
  : > "$TMP/lock"
  flock "$TMP/lock" sleep 2 &
  sleep 0.5
  start=$SECONDS
  run_deploy "$TAG_B"
  wait
  [ $((SECONDS - start)) -ge 1 ] || { echo "deploy did not wait for lock"; exit 1; }
  echo "case 4 (lock wait) OK"
else
  echo "case 4 SKIPPED: flock not available on this host"
fi
echo "deploy script tests OK"
