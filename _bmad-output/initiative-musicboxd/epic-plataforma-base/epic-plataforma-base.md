---
tracker_id: "MBD-3"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-3"
tracker_status: "backlog"
key: ""
type: epic
title: "Platform baseline"
parent: initiative-musicboxd
covers: []
after: []
assignee: ""
risk: medium
estimate: ""
estimate_basis: "envelope"
---

# Platform baseline

## Description

Stands up the deployable skeleton every other epic ships into: the `api/`, `web/`, `deploy/` repo layout from the architecture spine, CI that builds arm64 images, and the one-EC2-host runtime (Docker Compose: `api`, `postgres`, `nginx`) with TLS, backups, and cost/abuse guardrails. Nothing here is user-facing; it exists so CAP-1 through CAP-13 have somewhere real to land.

## Outcome

A "hello world" API and SPA are reachable over HTTPS on the production EC2 host, deployed by CI, with Postgres backed up and alarms live — before any capability epic ships its first feature.

## Requirements

No SPEC capability maps here; this epic exists to satisfy architecture invariants that bind all capabilities. Cited by section, not id:

- ARCHITECTURE-SPINE.md#ad-11 — Runtime topology and data safety
- ARCHITECTURE-SPINE.md#ad-10 — Cost and abuse guardrails
- ARCHITECTURE-SPINE.md#ad-7 — API contract is the single client interface (OpenAPI/springdoc scaffold)
- ARCHITECTURE-SPINE.md#stack — pinned versions, lockfiles

## Done when

1. The API and SPA are deployed to the EC2 host via CI (GitHub Actions, arm64 images) and reachable over HTTPS with a valid, auto-renewing certificate.
2. Only ports 80/443 are public; Postgres is reachable only on the internal Docker network; shell access is via SSM or IP-restricted SSH.
3. A scheduled `pg_dump` to S3 runs and a restore from it has actually been exercised once.
4. AWS Budgets alerts fire at 50/80/100%, and both rate-limit layers (nginx `limit_req`, Spring per-user limits) are configured and demonstrably active.
5. An empty OpenAPI doc is served from the API and the SPA's generated TypeScript client builds from it.

## Boundaries

Infrastructure and scaffolding only — no module business logic (`accounts`, `catalog`, etc. are empty shells or absent until their own epic). Not responsible for SES (owned by epic-contas-acesso) or S3 image bucket policy (owned by epic-catalogo-busca), only for the host, CI, and DB safety net.

- Touch point: none — this epic is itself the platform touch point every later epic builds on.

## References

- architecture — `../../planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`, sections AD-7, AD-10, AD-11, Stack, Structural Seed

## Notes

- Assumption: this epic is not itself a SPEC capability, so `covers` is empty per the tree check's rule for infrastructure-only epics; every line here cites an architecture section instead.
- Decision: production domain left undecided; entry 5 ships against a placeholder domain, swapped later (user, 2026-09-27).
- Decision: container registry is GHCR, not ECR — simpler auth from GitHub Actions, no extra IAM (user, 2026-09-27).
- Decision: epic closes with an end-to-end suite (entry 11) after the refactor sweep, even with no formal SPEC test plan for this epic (user, 2026-09-27).
- Tracer bullet: entry 1. Sequencing: app lane (1→2→3→6) and infra lane (4→5→7, 4→8) run in parallel; entry 9 bridges both. Entries 10–11 close the epic.
- Unknown: entry 4's IAM role scopes to the image bucket before epic-catalogo-busca creates it (AD-9) — coordinate the bucket name/ARN across epics before entry 4 starts.

