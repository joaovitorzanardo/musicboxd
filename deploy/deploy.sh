#!/usr/bin/env bash
# Deploys the sha-<40hex> image tag built by CI. Runs on the host (via SSM).
# Exit 0 live+healthy; 1 failed and previous tag restored; 2 bad input (nothing touched).
set -euo pipefail

TAG=${1:-}
ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
SRC_DIR=${SRC_DIR:-/opt/musicboxd-src}
STACK_DIR=${STACK_DIR:-/opt/musicboxd}
LOCK_FILE=${LOCK_FILE:-/var/lock/musicboxd-deploy.lock}
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-180}
GIT_REF=${GIT_REF:-origin/main}

[[ $TAG =~ ^sha-[0-9a-f]{40}$ ]] || { echo "refusing tag '$TAG' (want sha-<40 hex>)" >&2; exit 2; }

exec 9>"$LOCK_FILE"; flock 9          # serialize concurrent deploys; later one waits

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$STACK_DIR/docker-compose.prod.yml")
PREV=$(sed -n 's/^IMAGE_TAG=//p' "$ENV_FILE" | tail -1)

set_tag() {  # sed alone is a silent no-op when the line is missing, so append and verify
  if grep -q '^IMAGE_TAG=' "$ENV_FILE"; then
    sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" "$ENV_FILE"
  else
    printf '\nIMAGE_TAG=%s\n' "$1" >> "$ENV_FILE"
  fi
  grep -q "^IMAGE_TAG=$1\$" "$ENV_FILE"
}

# Healthy = at least one service listed, every service running, and api reports healthy.
wait_healthy() {
  local end=$((SECONDS + HEALTH_TIMEOUT))
  while (( SECONDS < end )); do
    if "${COMPOSE[@]}" ps --format '{{.Service}} {{.State}} {{.Health}}' \
        | awk 'NF==0{next} {n++} $1=="api"{api=1; if($3!="healthy")bad=1} $2!="running"{bad=1}
               END{exit (n==0||!api||bad)}'; then return 0; fi
    sleep 3
  done
  return 1
}

# errexit is disabled inside functions called from `if`, so chain explicitly.
apply() {  # $1 = tag
  set_tag "$1" && "${COMPOSE[@]}" pull && "${COMPOSE[@]}" up -d --remove-orphans && wait_healthy
}

git -C "$SRC_DIR" fetch --quiet origin
git -C "$SRC_DIR" checkout --quiet --detach "$GIT_REF"
# --remove-destination unlinks first, so this running script (possibly launched from STACK_DIR)
# keeps its old inode instead of being overwritten mid-read.
cp -r --remove-destination "$SRC_DIR/deploy/." "$STACK_DIR/"      # compose + nginx template ship with the repo

if apply "$TAG"; then
  echo "Deployed $TAG"
else
  echo "Deploy of $TAG failed; rolling back to ${PREV:-<none>}" >&2
  "${COMPOSE[@]}" logs --tail 50 >&2 || true
  if [[ -n $PREV ]]; then apply "$PREV" || echo "Rollback to $PREV also failed" >&2; fi
  exit 1
fi
