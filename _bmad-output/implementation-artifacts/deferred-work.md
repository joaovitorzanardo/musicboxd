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
