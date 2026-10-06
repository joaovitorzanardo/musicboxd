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
  *" ps "*)
    echo "api running $(cat "$STUB_TMP/health")"
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
HAVE_FLOCK=1
if ! command -v flock >/dev/null 2>&1; then
  HAVE_FLOCK=0
  printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/flock"   # no-op so deploy.sh runs here
fi
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
