# MBD-10 CD Deploys CI Images to the EC2 Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A push to `main` results in the EC2 host running the freshly built image tags within the same workflow run, with no manual step.

**Architecture:** Add a `deploy` job to `.github/workflows/publish-images.yml` that runs after both `publish` matrix legs succeed. It authenticates to AWS with GitHub OIDC (short-lived role credentials, no static keys) and runs `/opt/musicboxd/deploy.sh <sha-tag>` on the host through SSM Run Command (no SSH, port 22 stays closed). `deploy.sh` refreshes the host's copy of `deploy/` from the repo, pins `IMAGE_TAG` in `stack.env`, pulls, runs `up -d`, waits for health, and rolls back to the previous tag if the new stack is unhealthy. The job fails (and the run goes red) if the script fails.

**Tech Stack:** GitHub Actions, `aws-actions/configure-aws-credentials@v4` (OIDC), AWS SSM Run Command, Docker Compose v2, bash.

**Spec:** Jira MBD-10 / `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-cd-deploys-ci-images-to-the-ec2-host.md`; `ARCHITECTURE-SPINE.md` AD-11 ("Deploys come from CI", no static AWS keys, SSM-only shell). Depends on MBD-7 (images, done) and MBD-9 (prod stack, done).

## Global Constraints

- AC: "A push to main results in the EC2 host running the new image tags within the workflow's run, with no manual step."
- No static AWS keys anywhere (AD-11): GitHub to AWS via OIDC only; the host keeps using its instance role.
- No SSH, port 22 never opened; the host is reached only via SSM (MBD-8).
- Secrets stay in `/etc/musicboxd/*.env` on the host; nothing secret in the repo or workflow. AWS role ARN / instance ID go in GitHub repo **variables** (not secrets, not sensitive) or are inlined.
- Deploy the immutable `sha-<40 hex>` tag from the same commit, never `latest`, so the run proves which build is live.
- Images are linux/arm64; Compose v2; existing `docker-compose.prod.yml` and `publish-images.yml` build behavior unchanged.
- Out of scope: Postgres backups (MBD-11), rate limiting (MBD-13), cert expiry alarm.

## Review Focus

- Concurrent pushes: two deploys must not interleave on the host. Expect serialization (workflow `concurrency`) and a `flock` on the host so the later one waits.
- New image unhealthy / `up -d` fails: host must roll back to the previous tag and the workflow must go red, not leave a half-updated stack silently.
- Stale host files: `/opt/musicboxd` is a copy of `deploy/`; a compose/nginx template change merged to main must reach the host with the images, or the new image runs against old config.
- Malformed tag input (empty, `latest`, shell metacharacters) must be rejected before touching `stack.env`; it is interpolated into an SSM shell command.
- SSM command succeeds at the transport level but the script exits non-zero: the job must fail and show the remote output (`aws ssm wait` alone hides the stderr).

## File Structure

- Create `deploy/deploy.sh`: host-side deploy (lock, validate tag, sync files, pin tag, pull, up, health wait, rollback).
- Create `deploy/tests/test-deploy.sh`: runs `deploy.sh` against a stub `docker` and temp dirs.
- Modify `.github/workflows/publish-images.yml`: add `deploy` job.
- Create `deploy/aws/github-deploy-role.json` (trust + permission policy documents) for the one-time manual AWS setup.
- Modify `deploy/runbook-aws-host.md`: MBD-10 section (one-time setup, verification, rollback, operations note update).

---

### Task 1: Host-side `deploy.sh` with tests

**Files:**
- Create: `deploy/deploy.sh`
- Test: `deploy/tests/test-deploy.sh`

**Interfaces:**
- Produces: `deploy.sh <tag>`; env overrides `ENV_FILE` (default `/etc/musicboxd/stack.env`), `SRC_DIR` (default `/opt/musicboxd-src`), `STACK_DIR` (default `/opt/musicboxd`), `LOCK_FILE` (default `/var/lock/musicboxd-deploy.lock`), `HEALTH_TIMEOUT` seconds (default 180), `GIT_REF` (default `origin/main`). Exit 0 = new tag live and healthy. Exit 1 = failed, previous tag restored. Exit 2 = bad input, nothing touched.

- [ ] **Step 1: Write the failing test**

`deploy/tests/test-deploy.sh` (same style as `test-compose-config.sh`): build a temp sandbox with `stack.env` (`IMAGE_TAG=sha-old`), a stub `docker` first on `PATH` that logs its args to `$TMP/docker.log` and, for `compose ... ps --format json`/health query, reads its answer from `$TMP/health` (`healthy` or `unhealthy`), and a stub `git` that logs and succeeds (SRC_DIR is a plain dir containing `deploy/docker-compose.prod.yml`). Cases:

```bash
TAG=sha-$(printf 'a%.0s' {1..40})
# 1 happy path
echo healthy > "$TMP/health"
ENV_FILE=... SRC_DIR=... STACK_DIR=... LOCK_FILE="$TMP/lock" HEALTH_TIMEOUT=3 ./deploy.sh "$TAG"
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
grep -q 'compose.* pull' "$TMP/docker.log"; grep -q 'compose.* up -d' "$TMP/docker.log"
[ -f "$TMP/stack/docker-compose.prod.yml" ]            # synced from SRC_DIR/deploy
# 2 unhealthy -> rollback, exit 1
echo unhealthy > "$TMP/health"; : > "$TMP/docker.log"
if ./deploy.sh "sha-$(printf 'b%.0s' {1..40})"; then echo "expected failure"; exit 1; fi
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"            # restored previous tag
[ "$(grep -c 'compose.* up -d' "$TMP/docker.log")" = 2 ]  # new, then rollback
# 3 bad tags exit 2 and leave stack.env untouched
for bad in "" latest 'sha-x; rm -rf /' "sha-${TAG#sha-}0"; do
  set +e; ./deploy.sh "$bad"; rc=$?; set -e; [ $rc = 2 ]
done
grep -q "^IMAGE_TAG=$TAG$" "$TMP/stack.env"
# 4 lock held -> second invocation waits, does not interleave (hold lock with flock in background 2s, assert deploy finishes after it)
echo "deploy script tests OK"
```

- [ ] **Step 2: Run to verify it fails**

Run: `bash deploy/tests/test-deploy.sh`
Expected: FAIL (`deploy/deploy.sh: No such file`).

- [ ] **Step 3: Write minimal implementation**

`deploy/deploy.sh`:

```bash
#!/usr/bin/env bash
# Deploys the sha-<40hex> image tag built by CI. Runs on the host (via SSM) from /opt/musicboxd.
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

set_tag() { sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" "$ENV_FILE"; }

wait_healthy() {  # every service running, api healthy
  local end=$((SECONDS + HEALTH_TIMEOUT))
  while (( SECONDS < end )); do
    if "${COMPOSE[@]}" ps --format '{{.Service}} {{.State}} {{.Health}}' \
        | awk '$1=="api"&&$3!="healthy"{bad=1} $2!="running"{bad=1} END{exit bad}'; then return 0; fi
    sleep 3
  done
  return 1
}

apply() {  # $1 = tag
  set_tag "$1"
  "${COMPOSE[@]}" pull
  "${COMPOSE[@]}" up -d --remove-orphans
  wait_healthy
}

git -C "$SRC_DIR" fetch --quiet origin
git -C "$SRC_DIR" checkout --quiet --detach "$GIT_REF"
cp -r "$SRC_DIR/deploy/." "$STACK_DIR/"      # compose + nginx template ship with the images

if apply "$TAG"; then
  echo "Deployed $TAG"
else
  echo "Deploy of $TAG failed; rolling back to ${PREV:-<none>}" >&2
  "${COMPOSE[@]}" logs --tail 50 >&2 || true
  [[ -n $PREV ]] && apply "$PREV" || true
  exit 1
fi
```

Adjust the stub/health query in the test to match the `ps --format` line the stub prints (`api running healthy`).

- [ ] **Step 4: Run to verify it passes**

Run: `bash deploy/tests/test-deploy.sh`
Expected: `deploy script tests OK`. Also `shellcheck deploy/deploy.sh` clean if available.

- [ ] **Step 5: Commit**

```bash
git add deploy/deploy.sh deploy/tests/test-deploy.sh
git commit -m "Add host-side deploy script with health check and rollback (MBD-10)"
```

---

### Task 2: One-time AWS setup artifacts (OIDC role) — HITL

**Files:**
- Create: `deploy/aws/github-deploy-role.json`
- Modify: `deploy/runbook-aws-host.md`

**Interfaces:**
- Produces: role `musicboxd-gha-deploy` ARN and instance ID, consumed by Task 3 as GitHub repo variables `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION` (`us-east-1`), `DEPLOY_INSTANCE_ID` (`<INSTANCE_ID>`).

- [ ] **Step 1: Write the policy documents.** `github-deploy-role.json` holds (a) trust policy: federated principal `token.actions.githubusercontent.com`, `aud=sts.amazonaws.com`, `sub` equals `repo:joaovitorzanardo/musicboxd:ref:refs/heads/main` (only main can assume it); (b) permission policy: `ssm:SendCommand` on the `AWS-RunShellScript` document ARN and on the instance ARN, `ssm:GetCommandInvocation` and `ssm:ListCommandInvocations` on `*` (these do not support resource scoping). Nothing else.
- [ ] **Step 2: Runbook section "MBD-10: CD".** Document the manual console/CLI steps (human, since they touch IAM): create the OIDC provider if the account has none (`aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com`), create the role, set the three GitHub repo variables, and on the host: `sudo install -m 755 /opt/musicboxd-src/deploy/deploy.sh /opt/musicboxd/deploy.sh` (first time only; afterwards `deploy.sh` is refreshed by its own sync), confirm `/opt/musicboxd-src` can `git fetch` (repo public) and that GHCR packages are public (MBD-9 decision).
- [ ] **Step 3: Verify the role by simulation.** `aws iam simulate-principal-policy` expects `ssm:SendCommand` allowed on the instance and `ec2:DescribeInstances` / `s3:*` implicitDeny. Record results in a table like MBD-9's.
- [ ] **Step 4: Commit** `deploy/aws/github-deploy-role.json` and runbook.

---

### Task 3: `deploy` job in the workflow

**Files:**
- Modify: `.github/workflows/publish-images.yml`

**Interfaces:**
- Consumes: `deploy.sh <tag>` (Task 1); repo variables from Task 2. The image tag `sha-${{ github.sha }}` matches the `type=sha,format=long` tag already pushed by `docker/metadata-action`.

- [ ] **Step 1: Add the job** (after `publish`; `workflow_dispatch` runs deploy too, which is useful for re-deploys):

```yaml
  deploy:
    needs: publish
    runs-on: ubuntu-24.04-arm
    # Serialize deploys; never cancel one mid-flight (would leave the host half-updated).
    concurrency:
      group: deploy-production
      cancel-in-progress: false
    permissions:
      id-token: write
      contents: read
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.AWS_DEPLOY_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}

      - name: Deploy on EC2 via SSM
        env:
          INSTANCE_ID: ${{ vars.DEPLOY_INSTANCE_ID }}
          TAG: sha-${{ github.sha }}
        run: |
          set -euo pipefail
          CMD_ID=$(aws ssm send-command \
            --instance-ids "$INSTANCE_ID" \
            --document-name AWS-RunShellScript \
            --timeout-seconds 600 \
            --parameters "commands=[\"cd /opt/musicboxd && ./deploy.sh $TAG\"]" \
            --query Command.CommandId --output text)
          aws ssm wait command-executed --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" || true
          aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
            --query '{status:Status,out:StandardOutputContent,err:StandardErrorContent}' --output json
          STATUS=$(aws ssm get-command-invocation --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" --query Status --output text)
          [ "$STATUS" = Success ]
```

`wait ... || true` then an explicit status check ensures the remote stdout/stderr is always printed and the step fails on any non-`Success` status. Note SSM truncates output at 24,000 characters, which is enough for `deploy.sh`'s logs.

- [ ] **Step 2: Lint.** `actionlint .github/workflows/publish-images.yml` (or `docker run --rm -v "$PWD:/r" rhysd/actionlint -C /r`) passes.
- [ ] **Step 3: Commit** `Add CD job deploying CI images over SSM (MBD-10)`.

---

### Task 4: End-to-end verification and docs (HITL)

**Files:**
- Modify: `deploy/runbook-aws-host.md`

- [ ] **Step 1: Merge to `main` and watch the run.** `publish` then `deploy` both green. The `deploy` log shows `Deployed sha-<full sha>`.
- [ ] **Step 2: Confirm on the host (SSM):** `sudo docker compose --env-file /etc/musicboxd/stack.env -f /opt/musicboxd/docker-compose.prod.yml images` shows `sha-<sha>` for `api` and `nginx`; `curl -sS -o /dev/null -w "%{http_code}\n" https://musicboxd.com.br/api/v1/api-docs` returns 200.
- [ ] **Step 3: Failure drill.** Run the workflow with a deliberately broken image (e.g. temporarily set an invalid `api` start command on a throwaway branch via `workflow_dispatch`, or run `deploy.sh` by hand with a nonexistent `sha-000...0` tag): expect the job to fail, the host back on the previous tag, site still 200.
- [ ] **Step 4: Concurrency check.** Push two commits in quick succession; the second `deploy` waits for the first and the final live tag is the newer commit.
- [ ] **Step 5: Update the runbook** "Operations notes": replace "Deploying new images (until MBD-10)" with the CD flow, manual redeploy (`workflow_dispatch`) and manual rollback (`sudo /opt/musicboxd/deploy.sh sha-<old>`). Fill the verification results table. Commit.

---

## Self-Review

- Spec coverage: AC "push to main, host on new tags, within the run, no manual step" → Tasks 1+3 (tag is `sha-${{ github.sha }}`, deployed in the same run), proven by Task 4.
- Review Focus coverage: concurrency → Task 1 test 4 + Task 3 `concurrency`; rollback → Task 1 test 2 + Task 4 step 3; stale host files → Task 1 sync and test 1; malformed tag → Task 1 test 3; SSM output/exit status → Task 3 step 1.
- Names consistent: `deploy.sh`, `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `DEPLOY_INSTANCE_ID`, tag format `sha-<40 hex>` across tasks.

## Open decisions for review

1. **Repo public?** `deploy.sh` assumes `/opt/musicboxd-src` can `git fetch` without credentials (the MBD-9 runbook clones over https). If the repo is private, use a read-only deploy key on the host.
2. **GitHub Environment approval gate** (`environment: production` with required reviewer) would add a manual step and so contradicts the AC. Plan omits it.
3. **First-time bootstrap:** `deploy.sh` is not on the host until it is installed once by hand (Task 2 step 2).
