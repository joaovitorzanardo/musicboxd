# deploy/tests/lib.sh - helpers sourced by the deploy/tests suites. Callers set $TMP and create $TMP/bin first,
# and chmod +x "$TMP/bin/"* afterwards.

expect_rc() {  # expect_rc <code> <command...>; output lands in $TMP/out
  local want=$1; shift
  set +e; "$@" > "$TMP/out" 2>&1; local rc=$?; set -e
  [ "$rc" = "$want" ] || { echo "expected rc=$want, got $rc:"; cat "$TMP/out"; exit 1; }
}

# HAVE_FLOCK=1 when the host has flock; otherwise 0 plus a no-op $TMP/bin/flock so the scripts under test run.
stub_flock_if_missing() {
  HAVE_FLOCK=1
  command -v flock >/dev/null 2>&1 && return 0
  HAVE_FLOCK=0
  printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/flock"
}
