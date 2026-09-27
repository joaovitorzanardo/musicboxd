---
tracker_id: ""
key: ""
type: initiative
title: "Musicboxd MVP: the core loop of search, rate, and follow"
parent: none
covers: [CAP-1, CAP-2, CAP-3, CAP-4, CAP-5, CAP-6, CAP-7, CAP-8, CAP-9, CAP-10, CAP-11, CAP-12, CAP-13]
after: []
assignee: ""
risk: medium
estimate: ""
estimate_basis: "envelope"
---

# Musicboxd MVP: the core loop of search, rate, and follow

## Description

Musicboxd is a music tracker and social network in the spirit of Letterboxd, built for the founder and friends. The SPEC (`../specs/spec-musicboxd/SPEC.md`) is the canonical contract for what ships; this initiative delivers its thirteen capabilities to production on the architecture spine's modular monolith (Spring Boot + React SPA, one EC2 host).

## Outcome

The builder uses Musicboxd day to day: the full core loop (search → rate Album/Songs → optional Review) and the Feed work end to end with no workarounds. This is the SPEC's own success signal (`SPEC.md#success-signal`), not a restatement.

## Requirements

The requirement source is the SPEC's Capabilities section — CAP-1 through CAP-13 (`../specs/spec-musicboxd/SPEC.md#capabilities`). Each epic's `covers` cites these ids directly; there is no separate initiative-level spec.

## Done when

1. CAP-1 through CAP-13 are live in production (the EC2 host, TLS, no feature flags) — not merely coded.
2. A Guest can read Profiles, Reviews, Albums, and Songs with no account; every write action prompts sign-up (CAP-1).
3. After a User gives an Album Score, a follower's Feed shows it at the top, newest first (CAP-10).
4. Postgres backups run on schedule and a restore has actually been exercised (AD-11).
5. AWS Budgets alerts (50/80/100%) and both rate-limit layers (nginx, Spring per-user) are active (AD-10).

## Boundaries

Scope is exactly the SPEC's thirteen capabilities — see `SPEC.md#non-goals` and `SPEC.md#constraints` for what is excluded (artist accounts, computed album scores, moderation, everything in `roadmap.md`). The boundary between epics follows the architecture spine's module ownership (AD-1): one epic per module or tightly-coupled module group that one owner takes to production.

Tracer path: Staff cadastra um álbum (epic-catalogo-busca) → uma pessoa cria conta (epic-contas-acesso) → busca o álbum e dá uma nota (epic-ciclo-avaliacao) → um seguidor vê a avaliação no Feed (epic-social-feed).

- Touch point: Amazon SES — email verification at signup; owner: epic-contas-acesso
- Touch point: S3 image bucket / `uploads` module — introduced for album art in epic-catalogo-busca, reused for avatar and cover images in epic-perfil-favoritos; owner: epic-catalogo-busca
- Touch point: EC2 host, Docker Compose, nginx, CI, backups, AWS Budgets — owner: epic-plataforma-base

## References

- spec — `_bmad-output/specs/spec-musicboxd/SPEC.md`, section Capabilities
- constraint — same SPEC, section Constraints (privacy, catalog ownership, rating scale)
- architecture — `_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`, sections Invariants & Rules and Capability → Architecture Map
- ux — `_bmad-output/planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md`
- prd — `_bmad-output/planning-artifacts/prds/prd-teste-2026-09-25/prd.md`, for history only

## Notes

- Source conflict: SPEC's Open Questions — the architecture spine documents numbered pages for catalog search (consistency table, "Pagination") while the SPEC (Capabilities CAP-4, Constraints) says dropdown-only, no results page. Decision: dropdown-only wins per the SPEC's own precedence rule ("where it diverges from the PRD it wins for UX"); the architecture's pagination note is stale for this capability. 2026-09-27.
- Source conflict: PRD/architecture AD-6 allow Staff to remove a User's Review (hard delete via an admin endpoint); the SPEC lists "Moderation or report flow in the MVP, including Staff removal of Reviews" as a non-goal. Decision: no Staff-review-removal in the MVP; the SPEC wins as the canonical contract. AD-6's admin-delete endpoint is deferred with the rest of moderation. 2026-09-27.
- Open question (from SPEC): SES sandbox exit and SPF/DKIM timing — blocks email verification going live; owned by epic-contas-acesso.
- Open question (from SPEC): Catalog data source and cover-art licensing are deferred; the catalog stays manual (Staff-entered) for the whole MVP, which epic-catalogo-busca builds for.
- Decision: estimation stays off for this initiative (default), per user, 2026-09-27.
- Decision: the S3 image bucket is named `musicboxd-images` (single bucket; key prefixes avatar/, cover/, album-art/) — fixed here so epic-plataforma-base's IAM role (which precedes the bucket's own creation in epic-catalogo-busca) and the bucket's own policy converge on one literal ARN, 2026-09-27.
