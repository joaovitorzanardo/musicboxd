# MBD-14 Refactor Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close out the platform-baseline epic's deferred scope and review leftovers with behavior-preserving cleanups only, and record the functional follow-ups that this sweep must not do.

**Architecture:** No new components. Three small cleanups to existing shell scripts, Compose comments and docs, each guarded by the existing `deploy/tests/*.sh` suites, which are the safety net (a pure refactor has no new behavior to drive with a failing test). Equivalence is proven by running every suite before and after, and by diffing the rendered `docker compose config` of both Compose files.

**Tech Stack:** Bash, Docker Compose, Spring Boot (Gradle), React/Vite (Vitest).

**Spec:** Jira MBD-14 / `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-refactor-sweep.md`. AC: *"Scope is set when this ticket starts, from the build records and deferred review findings; no functional change results."*

## Scope (set at ticket start, 2026-10-07)

Sources read: `_bmad-output/implementation-artifacts/deferred-work.md`, the tracer-bullet plan's Review Triage Log, `docs/superpowers/plans/*` (MBD-9, -10, -11, -13), `docs/runbooks/*`, and every file under `deploy/`, `api/src/main`, `web/src`.

| # | Finding | Source | Decision |
|---|---------|--------|----------|
| 1 | Hardcoded local-dev Postgres creds in `deploy/docker-compose.yml` | deferred-work.md entry 1 | **Resolved already** by MBD-9: prod reads `/etc/musicboxd/postgres.env`, and `test-compose-config.sh` asserts no dev creds. Keep the local creds and fix the stale comment that still says this is "entry 1.5's job". Mark resolved (Task 3) |
| 2 | OpenAPI client generation inside Docker builds | deferred-work.md entry 2 | **Resolved already** by MBD-7 (PR #4, `openapi` stage in `web/Dockerfile`). Mark resolved (Task 3) |
| 3 | `printf` in `deploy/deploy.sh:25-27` and `deploy/tests/test-deploy.sh:106-107,110-111` contains literal line breaks inside the quotes instead of `\n` (they were mangled in MBD-10's `1ee1268`) | code read | **Do**: rewrite as `'\nIMAGE_TAG=%s\n'`; the output bytes are identical (Task 1) |
| 4 | `expect_rc` duplicated in two test files; the no-op `flock` stub duplicated in three | code read | **Do**: extract `deploy/tests/lib.sh` (Task 2) |
| 5 | Stale "this ticket" comment in `deploy/docker-compose.yml:21-23,35-36` | code read | **Do** (Task 3) |
| 6 | README repo map lists `deploy/` as just "docker-compose, nginx", omits `docs/`, and has no test commands | code read | **Do** (Task 3) |
| 7 | nginx `/api/` proxy wiring has no automated test | tracer-bullet triage log | **Not here**: already owned by MBD-15 (closing e2e suite). Record (Task 3) |
| 8 | Certificate-expiry alarm (AD-11), backup-freshness alarm (>26h), real-domain swap | MBD-9 / MBD-11 plans | **Not here**: functional changes. Record as open follow-ups (Task 3) |
| 9 | `DemoRateLimitController` should be deleted once a real endpoint carries `@RateLimited` | MBD-13 Javadoc | **Not here**: no real endpoint exists yet. Record (Task 3) |
| 10 | Runbook verification tables still read `TODO` (`runbook-aws-host.md`, `runbook-mbd-10-cd-*.md`, `runbook-mbd-11-postgres-backups.md`) | runbooks | **Not here**: needs live-host evidence only the operator has. Record (Task 3) |

Considered and rejected: a shared `COMPOSE=(...)` helper for the five host scripts (the scripts run from different working directories, and `deploy.sh` is copied over itself mid-run, so a sourced helper adds risk for one line of savings); deduplicating `nginx.conf` against the prod template (they differ structurally: TLS and ACME); adding a CI job for the deploy tests (a functional change to CI, closer to MBD-15).

## Global Constraints

- No functional change: every script's stdout, stderr, exit codes, and the rendered Compose config stay identical.
- Shell files stay LF (`.gitattributes`: `*.sh text eol=lf`).
- Commit messages end with ` (MBD-14)`, matching the repo history, plus the Co-Authored-By trailer.
- Branch: `story/mbd-14-refactor-sweep`, cut from `origin/main` (local `main` is stale).

## Review Focus

1. **The `IMAGE_TAG` append when stack.env lacks the line**: the output must still be `\nIMAGE_TAG=<tag>\n` byte for byte. This is pinned by `test-deploy.sh` case 5 plus the byte check in Task 1 Step 2.
2. **Hosts without `flock` (Git Bash on Windows)**: after the extraction, `HAVE_FLOCK=0` must still skip the lock cases instead of failing. Pinned by Task 2 Step 4: expect `SKIPPED` lines here and `OK` lines on Linux.
3. **`lib.sh` must be sourced after `$TMP/bin` exists and before `chmod +x "$TMP/bin/"*`**, or the stub is not executable. This is pinned by running the stub-only suites in Task 2.
4. **Comment-only Compose edits really are comment-only.** Pinned by the `docker compose config` diff in Task 3 Step 1/Step 5.
5. **`set -e` interplay**: `expect_rc` toggles `set +e/-e`. When sourced, it must behave exactly as the inline copy did. Pinned by the restore suite's expected-failure cases (4, 4b, 5-8) in Task 4.

---

### Task 0: Branch and baseline

- [ ] **Step 1: Create the branch**

```bash
git fetch origin
git switch -c story/mbd-14-refactor-sweep origin/main
```

- [ ] **Step 2: Record the baseline (all must pass before touching anything)**

```bash
bash deploy/tests/test-deploy.sh
bash deploy/tests/test-backup-db.sh
bash deploy/tests/test-compose-config.sh
bash deploy/tests/test-nginx-conf.sh
bash deploy/tests/test-nginx-ratelimit.sh
bash deploy/tests/test-backup-restore.sh
(cd api && ./gradlew test)
(cd web && npm test)
```

Expected: each deploy suite ends with its `... OK` line. Here, the `flock` cases print `SKIPPED` on Git Bash. Gradle prints `BUILD SUCCESSFUL` and Vitest reports all tests passed. If anything fails, stop: the sweep needs a green baseline.

---

### Task 1: Readable `printf` escapes in deploy.sh and its test

**Files:**
- Modify: `deploy/deploy.sh:21-30`
- Modify: `deploy/tests/test-deploy.sh:104-116`

**Interfaces:** none (no signatures change).

- [ ] **Step 1: Replace the mangled printf in `deploy/deploy.sh`**

Replace lines 21-30 with:

```bash
set_tag() {  # sed alone is a silent no-op when the line is missing, so append and verify
  if grep -q '^IMAGE_TAG=' "$ENV_FILE"; then
    sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" "$ENV_FILE"
  else
    printf '\nIMAGE_TAG=%s\n' "$1" >> "$ENV_FILE"
  fi
  grep -q "^IMAGE_TAG=$1\$" "$ENV_FILE"
}
```

- [ ] **Step 2: Prove the bytes are identical**

```bash
diff <(printf '
IMAGE_TAG=%s
' sha-x | od -c) <(printf '\nIMAGE_TAG=%s\n' sha-x | od -c) && echo IDENTICAL
```

Expected: `IDENTICAL`.

- [ ] **Step 3: Fix the same pattern in `deploy/tests/test-deploy.sh` case 5**

Replace lines 104-116 with:

```bash
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
```

- [ ] **Step 4: Confirm no literal-newline printf remains**

```bash
grep -nE "printf '$" deploy -r
```

Expected: no output.

- [ ] **Step 5: Run the suite**

Run: `bash deploy/tests/test-deploy.sh`
Expected: cases 1, 2, 2b, 2c, 2d, 3 and 5 print `OK`, case 4 prints `OK` or `SKIPPED`, and the last line is `deploy script tests OK`.

- [ ] **Step 6: Commit**

```bash
git add deploy/deploy.sh deploy/tests/test-deploy.sh
git commit -m "Use \\n escapes instead of literal line breaks in deploy printf (MBD-14)"
```

---

### Task 2: Shared test helpers in `deploy/tests/lib.sh`

**Files:**
- Create: `deploy/tests/lib.sh`
- Modify: `deploy/tests/test-backup-db.sh:36-41,51-55`
- Modify: `deploy/tests/test-backup-restore.sh:15-16,36-40`
- Modify: `deploy/tests/test-deploy.sh:36-41`

**Interfaces:**
- Produces: `expect_rc <code> <command...>` (runs the command with output in `$TMP/out`, and exits 1 with that output if the rc differs) and `stub_flock_if_missing` (sets `HAVE_FLOCK=1|0`; when 0, it writes a no-op `$TMP/bin/flock`). Both read `$TMP`, which the caller must set first.

- [ ] **Step 1: Create `deploy/tests/lib.sh`**

```bash
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
```

- [ ] **Step 2: Use it in `deploy/tests/test-backup-db.sh`**

Replace lines 36-41:

```bash
HAVE_FLOCK=1
if ! command -v flock >/dev/null 2>&1; then
  HAVE_FLOCK=0
  printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/flock"   # no-op so the script runs here
fi
chmod +x "$TMP/bin/"*
```

with:

```bash
source "$HERE/lib.sh"
stub_flock_if_missing
chmod +x "$TMP/bin/"*
```

Then delete the inline `expect_rc() { ... }` definition (old lines 51-55, four lines below `reset() {...}`).

- [ ] **Step 3: Use it in `deploy/tests/test-backup-restore.sh`**

Replace lines 15-16:

```bash
command -v flock >/dev/null 2>&1 || printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/flock"
chmod +x "$TMP/bin/"*
```

with:

```bash
source "$HERE/lib.sh"
stub_flock_if_missing
chmod +x "$TMP/bin/"*
```

Then delete the inline `expect_rc() { ... }` definition (old lines 36-40).

- [ ] **Step 4: Use it in `deploy/tests/test-deploy.sh`**

Replace the `HAVE_FLOCK=1` / `if ! command -v flock ...` / `fi` block (lines 36-40) with:

```bash
source "$HERE/lib.sh"
stub_flock_if_missing
```

Keep the existing `chmod +x "$TMP/bin/"*` line right after it.

- [ ] **Step 5: Confirm no duplicates remain**

```bash
grep -n "expect_rc() \|command -v flock" deploy/tests/*.sh
```

Expected: matches only in `deploy/tests/lib.sh`.

- [ ] **Step 6: Run the three affected suites**

```bash
bash deploy/tests/test-deploy.sh
bash deploy/tests/test-backup-db.sh
bash deploy/tests/test-backup-restore.sh
```

Expected: each ends with its `... OK` line. On Git Bash the lock cases (deploy 4, backup-db 7 and 10) print `SKIPPED`, which is the same as the baseline. Any case that passed at baseline and now fails means the source order is wrong (see Review Focus 3).

- [ ] **Step 7: Commit**

```bash
git add deploy/tests/lib.sh deploy/tests/test-backup-db.sh deploy/tests/test-backup-restore.sh deploy/tests/test-deploy.sh
git commit -m "Share expect_rc and the flock stub across deploy tests (MBD-14)"
```

---

### Task 3: Stale comments, deferred-work record and README

**Files:**
- Modify: `deploy/docker-compose.yml:21-23,35-36`
- Modify: `_bmad-output/implementation-artifacts/deferred-work.md` (full rewrite below)
- Modify: `README.md` (section "Estrutura do repositório" plus a new section "Testes")

- [ ] **Step 1: Snapshot the rendered local Compose config before editing**

```bash
SCRATCH=$(mktemp -d); echo "$SCRATCH"
docker compose -f deploy/docker-compose.yml config > "$SCRATCH/local-before.yml"
```

- [ ] **Step 2: Replace the stale comments in `deploy/docker-compose.yml`**

Lines 21-23 become:

```yaml
    # Local-dev-only credentials, intentionally hardcoded: this file never runs on the
    # host and publishes no Postgres port. Production reads them from
    # /etc/musicboxd/postgres.env (docker-compose.prod.yml, AD-11).
```

Lines 35-36 become:

```yaml
    # No ports published: Postgres is reachable only on the internal
    # Docker network (AD-11).
```

- [ ] **Step 3: Rewrite `_bmad-output/implementation-artifacts/deferred-work.md`**

```markdown
- source_plan: `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-tracer-bullet-api-spa-and-local-compose-talk-to-each-other-plan.md`
  summary: Replace the hardcoded local-dev Postgres credentials in deploy/docker-compose.yml with environment-file-based secrets.
  evidence: Real (musicboxd/musicboxd hardcoded in the compose file), but scoped to production secrets handling per AD-11 ("secrets live in environment files on the host, outside the repo"), which is entry 1.5's ("Production Compose stack behind nginx TLS") job, not this tracer-bullet ticket's.
  status: resolved (MBD-14, 2026-10-07)
  resolution: MBD-9 moved production to /etc/musicboxd/postgres.env (deploy/docker-compose.prod.yml); deploy/tests/test-compose-config.sh asserts the dev password never renders. The local-dev compose keeps its credentials on purpose (never deployed, no published port); its comment now says so.
- source_plan: none (native /plan for MBD-6, not a bmad-build plan file)
  summary: Wire OpenAPI client generation into web/Dockerfile and deploy/docker-compose.yml so Docker builds regenerate web/src/api-client/ from api's build output automatically.
  evidence: Deliberately out of scope for MBD-6 (user's choice): Docker's build context isolation means api/build/openapi.json isn't available to web's image build unless api/ is built first and the artifact is explicitly copied across -- that coordination belongs with entry 1.3 (CI/CD). Local dev (./gradlew generateOpenApiDocs or ./gradlew build, then npm run build) works today.
  status: resolved (MBD-7, PR #4; confirmed in MBD-14)
  resolution: web/Dockerfile's `openapi` stage runs ./gradlew generateOpenApiDocs and the web stage generates the client from it, for both local compose and CI images.
- source_plan: `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-tracer-bullet-api-spa-and-local-compose-talk-to-each-other-plan.md`
  summary: Automated test of nginx's /api/ proxy routing (SPA -> nginx -> api).
  evidence: Review Triage Log, carried defer; owned by epic entry 11.
  status: open -> MBD-15 (closing end-to-end suite)
- source_plan: `docs/superpowers/plans/2026-10-03-mbd-9-prod-compose-nginx-tls.md`
  summary: Certificate-expiry alarm required by AD-11.
  evidence: Listed as a deliberate follow-up in MBD-9 and MBD-10; not built. Functional change, so out of the MBD-14 sweep.
  status: open
- source_plan: `docs/superpowers/plans/2026-10-07-mbd-11-postgres-backups-s3.md`
  summary: Alarm when the newest backup in s3://$BACKUP_BUCKET/postgres/ is older than 26h.
  evidence: MBD-11 plan, "No alarm fires on a failed backup yet". Functional change, so out of the MBD-14 sweep.
  status: open
- source_plan: `docs/superpowers/plans/2026-10-03-mbd-9-prod-compose-nginx-tls.md`
  summary: Swap the placeholder DOMAIN for the real domain.
  evidence: MBD-9 explicitly out of scope; config/ops change, not code.
  status: open
- source_plan: `docs/superpowers/plans/2026-10-06-mbd-13-rate-limiting.md`
  summary: Delete api/.../demo/DemoRateLimitController (and its OpenAPI test) once a real endpoint carries @RateLimited.
  evidence: The controller's Javadoc; no real endpoint exists in the platform-baseline epic.
  status: open (first epic that rate-limits a real endpoint)
- source_plan: none (runbooks)
  summary: Fill the verification tables still marked TODO in docs/runbooks/runbook-aws-host.md, runbook-mbd-10-cd-setup.md, runbook-mbd-10-cd-verify.md and runbook-mbd-11-postgres-backups.md.
  evidence: Needs live-host evidence (resource IDs, run results) only the operator has.
  status: open (operator)
```

- [ ] **Step 4: Update `README.md`**

Replace the code block under `## Estrutura do repositório` with:

````markdown
```
musicboxd/
  api/      # aplicação Spring Boot
  web/      # SPA em React (Vite)
  deploy/   # Compose (local e produção), nginx, deploy.sh, backups, certbot, units systemd, políticas IAM e testes
  docs/     # runbooks de operação e planos de implementação
```
````

Then add before the final line (`Documentação de planejamento ...`):

````markdown
## Testes

- API: `cd api && ./gradlew test`
- Web: `cd web && npm test`
- Scripts de deploy (bash; os marcados com Docker precisam do daemon rodando):
  - `bash deploy/tests/test-deploy.sh`
  - `bash deploy/tests/test-backup-db.sh`
  - `bash deploy/tests/test-compose-config.sh` (Docker)
  - `bash deploy/tests/test-nginx-conf.sh` (Docker)
  - `bash deploy/tests/test-nginx-ratelimit.sh` (Docker)
  - `bash deploy/tests/test-backup-restore.sh` (Docker)

````

- [ ] **Step 5: Prove the Compose edit was comment-only**

```bash
docker compose -f deploy/docker-compose.yml config > "$SCRATCH/local-after.yml"
diff "$SCRATCH/local-before.yml" "$SCRATCH/local-after.yml" && echo IDENTICAL
bash deploy/tests/test-compose-config.sh
```

Expected: `IDENTICAL`, then `prod compose config OK`.

- [ ] **Step 6: Commit**

```bash
git add deploy/docker-compose.yml _bmad-output/implementation-artifacts/deferred-work.md README.md
git commit -m "Close resolved deferred work, record follow-ups, refresh README and stale comments (MBD-14)"
```

---

### Task 4: Full verification

- [ ] **Step 1: Re-run every suite from Task 0 Step 2**

Expected: identical results to the baseline, with the same `OK` and `SKIPPED` lines.

- [ ] **Step 2: Confirm the diff touches only the intended files**

```bash
git diff --stat origin/main...HEAD
```

Expected: exactly `README.md`, `_bmad-output/implementation-artifacts/deferred-work.md`, `deploy/deploy.sh`, `deploy/docker-compose.yml`, `deploy/tests/lib.sh`, `deploy/tests/test-backup-db.sh`, `deploy/tests/test-backup-restore.sh`, `deploy/tests/test-deploy.sh`, plus this plan file. Nothing under `api/` or `web/`.

- [ ] **Step 3: Confirm LF endings on shell files**

```bash
git ls-files --eol deploy/deploy.sh deploy/tests/*.sh
```

Expected: every line shows `i/lf`.

- [ ] **Step 4: Push and open the PR** (after the user confirms)

```bash
git push -u origin story/mbd-14-refactor-sweep
gh pr create --title "Refactor sweep (MBD-14)" --body "..."
```

The PR body lists the Scope table decisions, states "no functional change" with the evidence (suites green before and after, compose config identical), and ends with the Claude Code attribution line.

---

## Self-review

- **Spec coverage:** "scope set from build records and deferred findings" maps to the Scope table, which is persisted to `deferred-work.md` in Task 3. "No functional change" maps to Task 0 and Task 4 (same suites before and after), the byte check in Task 1, and the config diff in Task 3.
- **Placeholders:** none. The PR body `...` is written at PR time from the Scope table.
- **Names:** `expect_rc` and `stub_flock_if_missing`/`HAVE_FLOCK` are used consistently in Task 2.
