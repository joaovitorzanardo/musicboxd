---
tracker_id: "MBD-27"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-27"
tracker_status: "backlog"
key: ""
type: epic
title: "Catalog and search"
parent: initiative-musicboxd
covers: [CAP-3, CAP-4]
after: []
assignee: ""
risk: medium
estimate: ""
estimate_basis: "envelope"
---

# Catalog and search

## Description

The `catalog` module: Staff-only creation, editing, and hiding of Albums and Songs, the genre list Staff manages, and search over the Catalog and Users for anyone. Also introduces the `uploads` module's presigned-S3 flow, first used for album art, which epic-perfil-favoritos reuses for avatars and covers.

## Outcome

Staff can populate and curate the Catalog through an admin area; any visitor, logged in or not, can find an Album, Song, or User by name and open its page — with no results page, pagination, or shareable search URL (SPEC constraint).

## Requirements

- CAP-3 — Staff create, edit, hide Albums and Songs, assign Songs to Albums, manage the genre list; a hidden item is absent from search but opens by link; no delete; non-Staff cannot reach catalog management (`SPEC.md#capabilities`)
- CAP-4 — any visitor searches the Catalog (Albums, Songs) and Users by username, each opening its page; dropdown only, no results page (`SPEC.md#capabilities`)

## Done when

1. A Staff user creates an Album, adds Songs to it, and edits both; a non-Staff User gets no access to `/api/v1/admin/**`.
2. Hiding an Album or Song removes it from search results but it still opens at its direct link.
3. No delete operation exists for Albums or Songs anywhere in the API or SPA.
4. A search query returns matching non-hidden Albums, Songs, and Users in a single dropdown (Postgres `ILIKE` + `pg_trgm`), each entry opening its page; there is no dedicated results page or URL.
5. Staff can add and edit the genre list that epic-perfil-favoritos's favorite-genres feature reads from.
6. The `uploads` presign flow (client → presigned POST → S3 → confirm) is live and enforces content-type and size limits for album art.
7. A per-user Spring rate limit on upload-URL (presign) issuance is live (AD-10).
8. Deployed to production behind the epic-plataforma-base runtime.

## Boundaries

Owns the `catalog` Postgres schema. Search covers Albums, Songs, and Users (the last read from `accounts`/`profiles` via its own public API, per AD-1 — no cross-schema query). Genre list lives here (AD-13) even though `profiles` stores the ids a User picks. Resolves the SPEC/architecture conflict logged in the initiative's Notes: dropdown-only search, no numbered pages.

- Touch point: S3 image bucket — this epic stands up the `uploads` module and its presign contract; epic-perfil-favoritos reuses it unchanged for avatar/cover images.

## References

- spec — `../../specs/spec-musicboxd/SPEC.md`, section Capabilities (CAP-3, CAP-4)
- architecture — `../../planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`, sections AD-9 (uploads/S3), AD-12 (no delete), AD-13 (genre list ownership), Consistency Conventions (Search, Pagination)
- ux — `../../planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md`, flow "Cadastrar e ocultar album (Staff)"

## Notes

- Source conflict: resolved at the initiative level — dropdown-only search wins over the architecture's numbered-pages note for catalog search; see `../initiative-musicboxd.md` Notes.
- Open question (from SPEC): Catalog data source and cover-art licensing are deferred; this epic builds the manual (Staff-entry) path only, no import tooling.
