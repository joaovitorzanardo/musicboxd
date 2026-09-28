---
title: 'Tracer bullet: API, SPA, and local Compose talk to each other'
type: 'feature'
ticket: 1
created: '2026-09-27'
status: 'built'
route: 'full'
route_source: 'auto'
review: 'thorough'
review_source: 'auto'
lenses_ran: ['blind-hunter', 'edge-case-hunter', 'verification-gap', 'intent-alignment']
review_loop_iteration: 1
context: []
baseline_revision: 'e8fa4d6d8f05d436297d2b85c5e6e4b821540962'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The repo has no `api/`, `web/`, or `deploy/` yet — nothing proves the three pieces (Spring Boot API, React SPA, nginx) can actually run together before any real capability is built on top.

**Approach:** Scaffold the three directories per the architecture spine's Structural Seed: a minimal Spring Boot API with one health endpoint, a minimal React SPA screen that calls it, and a `docker-compose.yml` (api + postgres + nginx) that serves the SPA and proxies its API call through nginx.

## Boundaries & Constraints

**Always:** Follow AD-11 topology (only 80/443 public; Postgres reachable only on the Docker network); follow AD-7 (all behavior under `/api/v1`); use the pinned Stack versions (Java 25, Spring Boot 4.1.1, PostgreSQL 18, React 19.3, Vite 8); directory layout exactly `api/`, `web/`, `deploy/` per Structural Seed.

**Never:** No module business logic (`accounts`, `catalog`, etc.) — this ticket is infra-only; no CI/CD, EC2, TLS, backups, or rate limiting (later entries 3–8 in this epic); no auth.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | `docker compose up` in `deploy/` | Browser at `http://localhost` shows the SPA, which calls `/api/v1/health` through nginx and renders the response | No error expected |
| API not yet up | SPA loads before API health check resolves | SPA shows a loading/pending state, then the result once the call resolves | Fetch failure is caught and shown as a visible error state, not an unhandled rejection |

</frozen-after-approval>

## Code Map

- Repo root currently has no `api/`, `web/`, or `deploy/` — this is a from-scratch scaffold, nothing to reuse or avoid touching.
- `_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md` — Structural Seed (directory layout + Compose topology diagram), Stack table (pinned versions), AD-7 (`/api/v1` prefix), AD-11 (topology/ports).

## Tasks & Acceptance

**Execution:**
- [x] `api/build.gradle.kts`, `api/settings.gradle.kts` -- Gradle project, Java 25 toolchain, Spring Boot 4.1.1 / Spring Framework 7 plugin, `spring-boot-starter-web` -- minimal buildable Spring Boot module
- [x] `api/src/main/java/com/musicboxd/api/ApiApplication.java` -- `@SpringBootApplication` main class -- entry point
- [x] `api/src/main/java/com/musicboxd/api/health/HealthController.java` -- `GET /api/v1/health` returns `200 {"status":"ok"}` -- proves the API layer is reachable per AD-7's `/api/v1` prefix
- [x] `api/src/main/resources/application.yml` -- server port 8080, app name -- minimal runtime config, no DB wiring yet (Postgres runs in Compose but nothing reads/writes it in this ticket)
- [x] `api/Dockerfile` -- multi-stage: Gradle build stage, then a JRE (arm64-compatible) runtime stage -- image Compose runs; matches AD-11's arm64 requirement even though CI (entry 3) isn't built yet
- [x] `web/package.json`, `web/vite.config.ts`, `web/index.html` -- Vite 8 + React 19.3 scaffold -- minimal SPA project
- [x] `web/src/main.tsx`, `web/src/App.tsx` -- one screen that fetches `/api/v1/health` on load and renders status/loading/error state -- satisfies the ticket's verify step
- [x] `web/Dockerfile` -- multi-stage: `npm ci && npm run build`, then copy `dist/` into a stage nginx can mount/copy from -- static assets for nginx to serve
- [x] `deploy/nginx/nginx.conf` -- serves `web`'s built static files at `/`, proxies `/api/` to the `api` service -- single origin for SPA + API per AD-8's cookie/domain note (even though auth isn't in this ticket)
- [x] `deploy/docker-compose.yml` -- services `api`, `postgres` (image `postgres:18`, internal network only, no published port), `nginx` (publishes 80 only, depends on `api` and the built `web` assets) -- local topology mirroring AD-11's production shape minus TLS; added a healthcheck to `api` (bash `/dev/tcp`, since no wget/curl in the runtime image) and set nginx's `depends_on` to `condition: service_healthy`, so nginx never proxies to an API that hasn't finished Spring Boot's cold start
- [x] `.dockerignore` (root or per-service) -- excludes `node_modules`, build output, `.git` -- keeps build contexts small
- [x] `web/src/App.test.tsx`, `web/vite.config.ts` (test config), `web/src/test/setup.ts` -- Vitest + Testing Library unit tests for `App.tsx`'s own loading/ok/error state machine against a mocked `fetch` (not a claim of full-matrix coverage — see Design Notes), including an explicit assertion that the loading state renders before the resolved result
- [x] `web/src/App.tsx` -- guard the health response so a body without a string `status` renders the error state instead of `API status: undefined`; guard each `.then` callback with `if (controller.signal.aborted) return;` so an unmount racing an already-settled fetch never calls `setHealth` after unmount
- [x] `api/src/test/java/com/musicboxd/api/health/HealthControllerTest.java` -- `@WebMvcTest(HealthController.class)`, asserts `GET /api/v1/health` returns 200 and `{"status":"ok"}` -- proves the API's own AD-7 contract at the exact path nginx proxies to, run as part of the build
- [x] `.gitattributes` -- `api/gradlew text eol=lf`, and set `api/gradlew`'s git index mode to executable (`git update-index --chmod=+x`) -- prevents a future checkout on this `core.autocrlf=true` machine from corrupting the shebang or losing the executable bit

**Acceptance Criteria:**
- [x] Given the repo at this ticket's state, when running `docker compose up` from `deploy/`, then a browser at `http://localhost` loads the SPA.
- [x] Given the SPA has loaded, when it calls the health endpoint, then it displays the API's `{"status":"ok"}` response, reached through nginx (not by calling the API's port directly).
- [x] Given `postgres` is running in Compose, when inspecting published ports, then only nginx's port 80 is published to the host — Postgres has no host port mapping.
- [x] Given a fresh clone on a machine with `core.autocrlf=true`, when checking out `api/gradlew`, then it keeps LF line endings and its executable bit.
- [x] Given `./gradlew bootJar` runs, when `HealthControllerTest` executes, then it passes, asserting the exact `/api/v1/health` → `{"status":"ok"}` contract nginx proxies to.

## Implementation Notes

- Gradle wrapper pinned to **9.8.0**, not 8.x: the Spring Boot 4.1.1 Gradle plugin requires Gradle 8.14+ or 9.x. `org.gradle.toolchains.foojay-resolver-convention` (1.0.0) lets Gradle auto-provision a JDK 25 toolchain on a machine whose installed JDK is older.
- `web/Dockerfile`'s final stage is `nginx:1.27-alpine` itself (build stage `node:24-alpine` -> serve stage nginx with the built `dist/` and `deploy/nginx/nginx.conf` copied in), built with the **repo root** as context (`deploy/docker-compose.yml`'s `nginx` service: `context: ..`, `dockerfile: web/Dockerfile`). Keeps the topology at exactly three running services (`api`, `postgres`, `nginx`) per AD-11.
- Postgres 18's official image mounts its data directory at `/var/lib/postgresql`, not `.../data` (docker-library/postgres#1259); `docker-compose.yml` uses a `postgres_data` named volume at that path so `docker compose down` (without `-v`) doesn't discard data between restarts.
- Exact pinned versions: Vite `8.3.1`, React/`react-dom` `19.3.0`, `@vitejs/plugin-react` `6.1.1`, TypeScript `7.0.2`, Spring Boot Gradle plugin `4.1.1`, `io.spring.dependency-management` `1.1.7`, Vitest `5.0.2`, `@testing-library/react` `16.3.3`, `@testing-library/jest-dom` `7.0.1`, `jsdom` `30.1.1`.
- **Pass 1 review fixes applied directly** (see Plan Change Log, Loopback 1 — a full revert-and-rederive via a fresh step-03 subagent was skipped because the harness's destructive-action guard blocked the `git reset --hard` that step would have required, and it wasn't actually necessary: every fix below is additive/independent of the already-verified scaffold):
  - `api/gradlew`'s git index mode set to `100755` (`git update-index --chmod=+x`); added root `.gitattributes` (`api/gradlew text eol=lf`) so a future checkout on this `core.autocrlf=true` machine can't corrupt the shebang or drop the executable bit.
  - `deploy/docker-compose.yml`: added a healthcheck to `api` and changed nginx's `depends_on` to `condition: service_healthy`. The healthcheck can't use wget/curl — eclipse-temurin's `25-jre` image (Ubuntu 26.04 base) has neither — so it uses bash's `/dev/tcp` (`bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'`), confirmed available in that image. Verified: on `docker compose up`, nginx now waits for `api-1` to report `healthy` before starting (confirmed in compose output: "Container deploy-api-1 Healthy" precedes "Container deploy-nginx-1 Starting").
  - `web/src/App.tsx`: guarded the parsed body so a response without a string `status` renders the error state (`Malformed health response`) instead of `API status: undefined`; guarded every `.then` callback with `controller.signal.aborted` so an unmount racing an already-settled fetch can't call `setHealth` after unmount.
  - `web/src/App.test.tsx`: added an assertion that the loading state ("Checking API health…") renders before the mocked fetch resolves (previously only the post-resolve state was checked), and a new test for the malformed-response guard. Discovered while adding these that tests weren't being cleaned up between runs (RTL's auto-cleanup needs a global `afterEach`, which this project doesn't enable) — a prior test's async state update was leaking into the next test's DOM and causing false failures/passes; fixed by adding explicit `afterEach(cleanup)` in `web/src/test/setup.ts`.
  - `api/src/test/java/com/musicboxd/api/health/HealthControllerTest.java`: added a `@WebMvcTest` proving the API's own `/api/v1/health` → `{"status":"ok"}` contract. Spring Boot 4 modularized `@WebMvcTest` out of `spring-boot-test-autoconfigure` (which now only covers `jdbc`/`json` slices) into a new `spring-boot-webmvc-test` artifact, under the new package `org.springframework.boot.webmvc.test.autoconfigure` — discovered by inspecting the resolved dependency jars, since the old `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` import (valid in Boot 3.x) no longer exists. Added `testImplementation("org.springframework.boot:spring-boot-webmvc-test")` to `api/build.gradle.kts`. `./gradlew test` passes.
  - Postgres credentials in `docker-compose.yml` deliberately left as-is (hardcoded local-dev placeholders) — recorded in `deferred-work.md` as entry 1.5's ("Production Compose stack behind nginx TLS") job, not this ticket's.
- **Pass 2 review fixes applied directly** (see Review Triage Log, Pass 2 — all patch-routed, no loopback needed):
  - `web/src/App.test.tsx`: added a 4th test for the non-2xx (`!response.ok`) branch, previously untested.
  - `deploy/docker-compose.yml`: added `restart: on-failure` to all three services (a healthcheck-exhausted `api` previously left the stack stuck with no recovery); added a one-line comment on `postgres`'s credentials pointing to `deferred-work.md` so the deferral is visible to anyone editing the file directly.
  - `web/src/App.tsx`: the first `.then` (response handler) now also checks `controller.signal.aborted` before proceeding — it hadn't, contradicting the Pass 1 note's "each/every `.then`" claim (caught by edge-case-hunter).
  - Plan doc: removed a duplicated, empty `## Plan Change Log` heading left over from the Loopback 1 edit.
  - `api/gradle/wrapper/gradle-wrapper.properties`: added `distributionSha256Sum` for the pinned Gradle 9.8.0 distribution (fetched from `services.gradle.org`) — `validateDistributionUrl=true` was already set but had nothing to validate against.
  - `web/vite.config.ts`: `defineConfig` now imported from `vitest/config` instead of `vite`, so the `test` block is type-checked where the file is covered (it currently isn't, being outside `tsconfig.app.json`'s `include`; functionally unaffected either way since Vitest reads the config object at runtime regardless of which package's `defineConfig` produced it).

## Review Triage Log

### Pass 1 (lenses: blind-hunter, edge-case-hunter, verification-gap, intent-alignment)

Verdict counts: high 0, medium 1, low 4, false 4, maybe-false 0. (Deduplicated: verification-gap's finding, intent-alignment's divergence report, and blind-hunter's item 5 all describe the same root cause and are logged as one entry.)

| Finding | Verdict | Route | Evidence |
|---|---|---|---|
| The plan's Matrix Test Audit fix (`web/src/App.test.tsx`) only exercises `App.tsx` against a mocked `fetch`; nothing exercises nginx's `proxy_pass` or the real controller mapping, so the frozen I/O matrix's system-level rows ("through nginx", "reached through nginx not by calling the API's port directly") are unverified by any reproducible artifact — only by prose in Implementation Notes. (verification-gap, intent-alignment, blind-hunter #5) | medium | bad_plan | Confirmed: `App.test.tsx` calls `vi.stubGlobal('fetch', ...)` — fetch never leaves the process. No test/script touches `deploy/nginx/nginx.conf` or hits a real `api` container. Root cause is my own plan task ("Vitest + Testing Library unit tests covering the I/O & Edge-Case Matrix") which overclaimed coverage it can't deliver at the unit level — a plan-authoring gap, not a defect in the scaffolded code itself. |
| `api/gradlew` committed with mode `100644` (not `100755`); no `.gitattributes` pinning it to LF despite `core.autocrlf=true` being active in this repo | low | patch | Verified via `git ls-files -s api/gradlew` (100644) and `git config core.autocrlf` (true), no `.gitattributes` present. Real risk on a fresh checkout on this machine: LF shebang could become CRLF, breaking `./gradlew` both standalone and when `COPY`'d into the Linux Docker build stage. |
| `nginx`'s `depends_on: [api]` only waits for container start, not Spring Boot readiness; a request during the API's cold start can get a raw connection failure from nginx instead of the matrix's documented states | medium | patch | Confirmed: compose file has no healthcheck on `api` and no `condition: service_healthy` on nginx's `depends_on`. Real friction on every `docker compose up` per the plan's own Verification steps; fix is a standard Compose healthcheck, no scope creep. |
| `web/src/App.tsx` renders `API status: undefined` if the health response ever lacks a string `status` field | low | patch | Confirmed: `body.status` is read without a runtime guard past the TS cast. Currently unreachable (API and UI are in sync), but the fix (one guard clause) is trivial and cheap insurance for AD-7's contract. |
| `App.test.tsx`'s happy-path test never asserts the loading state ("Checking API health…") actually appears before the resolved result — only the post-resolve state is checked | low | patch | Confirmed by reading the test: it renders then immediately `await`s the resolved text; the loading path is exercised but never asserted. |
| Component's fetch `.then` chain can call `setHealth` after unmount if `abort()` races an already-settled fetch | low | patch | Real but narrow: `App` is the SPA's root component and is never unmounted in current usage, so the race is currently unreachable; fix (`if (controller.signal.aborted) return;` in each `.then`) is trivial and cheap to add now. |
| Postgres credentials (`musicboxd`/`musicboxd`) are hardcoded in `deploy/docker-compose.yml` | low | defer | Real, but this is the local-dev compose file; AD-11's "secrets live in environment files on the host, outside the repo" rule concerns the production runtime, which entry 1.5 ("Production Compose stack behind nginx TLS") owns per this epic's own sequencing notes. Not this ticket's problem to solve. |
| No timeout/retry on the health fetch — SPA shows "Checking API health…" indefinitely if the API is merely slow, not down | low | reject | The frozen I/O matrix only covers "not yet up" (a rejected fetch), not "slow" (a fetch that never settles); unlikely to matter for a local health check with sub-10s Spring Boot boot time, and a real fix (timeout + retry UX) is more than a trivial correction. |
| `deploy/nginx/nginx.conf`'s static-asset location has no cache-control/expires headers | low | reject | Cosmetic for a tracer-bullet ticket; unlikely to be encountered as a defect at this stage, and a real fix requires deciding a caching policy — more than a trivial correction. |
| `web/Dockerfile` copies `package.json` and `package-lock.json*` with a wildcard, "inconsistent" with `npm ci` requiring the lockfile | false | — | The lockfile is committed and present; the wildcard causes no actual failure or different behavior in this build. |
| No README documenting the two entry points (Compose vs. `npm run dev`) | false | — | No concrete failure scenario named — a vague completeness concern, not a defect at a specific location. |
| No lint/format tooling configured in `web/` | false | — | No concrete failure scenario named; `tsconfig`'s strict flags already catch the class of errors linting would add. |

### Pass 2 (lenses: blind-hunter, edge-case-hunter, verification-gap, intent-alignment)

Verdict counts: high 0, medium 0, low 8, false 4, maybe-false 0.

| Finding | Verdict | Route | Evidence |
|---|---|---|---|
| nginx `/api/` proxy routing (and the full SPA→nginx→api wiring) still has no automated test | low | defer (carried) | Same location/claim as Pass 1's bad_plan finding; code unchanged there by design. verification-gap and intent-alignment both confirm the plan now accurately documents this as out of scope, owned by epic entry 1.11 — no longer a plan-accuracy defect, just an accepted, already-logged scope boundary. |
| `App.tsx`'s non-2xx (`!response.ok`) branch had no test | low | patch | Confirmed: none of the 3 existing tests resolved `fetch` with a non-2xx status. Added a 4th test asserting the 500 case. |
| No `restart` policy on any Compose service; if `api` exhausts its healthcheck retries, the stack is stuck with no recovery | low | patch | Confirmed: no `restart` key anywhere in `docker-compose.yml`. Added `restart: on-failure` to all three services. |
| Implementation Notes claims the abort guard covers "each/every" `.then` callback, but only the second `.then` had the `controller.signal.aborted` check — the first (`response.json()` call) did not | low | patch | Confirmed by reading `App.tsx`. Added the same guard to the first `.then` so the claim is now accurate. |
| Plan doc had a duplicated, empty `## Plan Change Log` heading before the real one (with `## Review Triage Log` sandwiched between) | low | patch | Confirmed via `grep -n "^## "` — line 85 (empty) and line 108 (real content). Removed the duplicate. |
| `api/gradle/wrapper/gradle-wrapper.properties` has `validateDistributionUrl=true` but no `distributionSha256Sum` for the pinned Gradle 9.8.0 distribution | low | patch | Confirmed: no checksum field present. Added the official SHA-256 from `services.gradle.org`. |
| `web/vite.config.ts` imports `defineConfig` from `'vite'` instead of `'vitest/config'`, so the `test` block isn't type-checked (and the file sits outside every tsconfig's `include` regardless) | low | patch | Confirmed both halves: wrong import, and `tsconfig.app.json`'s `include: ["src"]` excludes `vite.config.ts`. Fixed the import for correctness; functionally unaffected either way since Vitest reads the object at runtime regardless of which package's `defineConfig` produced it. |
| Postgres credential deferral wasn't visible as a comment in `docker-compose.yml` itself | low | patch | Real discoverability gap for anyone editing the file directly without also checking `deferred-work.md`. Added a one-line comment pointing to it. |
| `HealthControllerTest` only covers the happy path, no unmapped-path/unsupported-method test | low | reject | Marginal value for this ticket's scope: the existing happy-path test already protects the exact contract (`/api/v1/health` → `{"status":"ok"}`) nginx depends on; testing framework-default 404/405 behavior doesn't add meaningful regression coverage for a tracer-bullet ticket. |
| `nginx.conf` has no security headers or cache-control for static assets | low | reject | Cosmetic for this ticket's scope; same reasoning as the Pass 1 reject on this class of finding — real fix requires a policy decision, not a trivial correction. |
| No README documenting how to run the tracer bullet | false | — | Same as Pass 1: no concrete failure scenario named. |
| `.gitattributes` scoped to `api/gradlew` only, not generalized/documented as intentional | false | — | No concrete failure scenario; scoping it to the one file with a real, demonstrated risk (the shebang script) rather than every text file is the correct, minimal scope. |
| `App.tsx` gives both `loading` and `ok` states `role="status"`, with nothing distinguishing them | false | — | The two are mutually exclusive at render (only one `HealthState` variant is ever active), so there's no real ambiguity for assistive tech or tests at runtime. |

## Plan Change Log

### Loopback 1 (bad_plan)

- **Triggering finding:** the plan's own Matrix Test Audit task claimed unit tests would cover the I/O & Edge-Case Matrix, but the matrix's rows describe system-level behavior (SPA → nginx → API) that a mocked-`fetch` unit test cannot verify.
- **Amendment:** Tasks & Acceptance now (a) adds a narrow API-side contract test (`HealthController`'s exact `/api/v1/health` mapping, via `@WebMvcTest`) instead of claiming the unit test covers the full matrix; (b) folds in the five patch-routed fixes from Pass 1 (gradlew mode/`.gitattributes`, nginx healthcheck, malformed-response guard, unmount guard, loading-state test assertion) so they're derived together with everything else; (c) Implementation Notes now states explicitly, in the Design Notes, that full three-service wiring proof remains the manual `docker compose up` + curl/browser check the ticket's own verify step describes, with automated proof of that exact wiring owned by this epic's closing end-to-end suite (entry 1.11) — not reattempted here.
- **Known-bad state avoided:** a plan/test-suite that reads as "matrix fully covered by automated tests" when the system-level rows are not, which would let a future change to `nginx.conf`'s `proxy_pass` or the controller's path silently break the tracer bullet's core proof with no automated signal.
- **Execution note:** the harness's destructive-action guard blocked a full `git reset --hard` to baseline, so the loopback was resolved by applying the amendment's fixes directly on top of the existing (already independently-verified) scaffold rather than via a full revert-and-rederive through a fresh step-03 subagent. This preserves the KEEP list below exactly, since nothing in it was touched.
- **KEEP:** every already-verified piece of the scaffold works and must be reproduced as-is — Gradle/Spring Boot module and Dockerfile, Vite/React SPA and Dockerfile, nginx config and Compose topology (three services, only nginx's port 80 published, Postgres on the internal network only, `/var/lib/postgresql` volume mount for Postgres 18), the `postgres_data` named volume, and the exact dependency versions already pinned and verified (Gradle wrapper 9.8.0, Vite 8.3.1, React 19.3.0, `@vitejs/plugin-react` 6.1.1, TypeScript 7.0.2, Spring Boot Gradle plugin 4.1.1, `io.spring.dependency-management` 1.1.7, Vitest 5.0.2, `@testing-library/react` 16.3.3, `@testing-library/jest-dom` 7.0.1, `jsdom` 30.1.1).

## Design Notes

Health endpoint is a small custom controller under `/api/v1/health`, not Spring Boot Actuator's `/actuator/health` — keeps the tracer bullet consistent with AD-7 (single `/api/v1` client interface) instead of introducing a second, unversioned endpoint namespace this early.

**Scope of automated coverage (set after Pass 1 review):** `web/src/App.test.tsx` proves the SPA's own loading/ok/error state machine against a mocked `fetch` — it does not and cannot prove nginx's `proxy_pass` or the controller's route mapping. A `@WebMvcTest` on `HealthController` proves the API's own `/api/v1/health` contract in isolation. Neither, individually or together, proves the full three-service wiring (browser → nginx → api) the ticket's own verify step describes — that remains a manual `docker compose up` + curl/browser check for this ticket. Automated proof of the full wiring is this epic's closing end-to-end suite (entry 1.11, "platform baseline proven together"), not this ticket's job to add.

## Verification

**Commands:**
- `docker compose -f deploy/docker-compose.yml up --build` -- expected: all three services start; no exit/crash loop; nginx's healthcheck-gated startup means it only proxies once `api` reports healthy
- `curl -s http://localhost/api/v1/health` -- expected: `{"status":"ok"}`
- `cd web && npm run test` -- expected: all tests pass, including the loading-state assertion
- `cd api && ./gradlew test` -- expected: `HealthControllerTest` passes
- `git ls-files -s api/gradlew` -- expected: mode `100755`

**Manual checks (if no CLI):**
- Open `http://localhost` in a browser; confirm the page renders the health status text (not a loading spinner stuck, not an error state).
