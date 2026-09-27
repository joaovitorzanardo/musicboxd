---
tracker_id: "MBD-34"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-34"
tracker_status: "backlog"
id: 7
type: story
title: "Dropdown search: Albums, Songs, and Users"
parent: epic-catalogo-busca
after: [2, 2.1]
risk: medium
---

# Dropdown search: Albums, Songs, and Users

## Description

Adds Postgres ILIKE + pg_trgm search across non-hidden Albums and Songs, plus Users by username (read from accounts/profiles via its own public API, no cross-schema query), returned in one dropdown with no results page, pagination, or shareable URL.

## Acceptance Criteria

Verify: A query returns matching non-hidden Albums, Songs, and Users in one dropdown, each opening its page; a hidden Album or Song is absent from results; there is no results-page route.

## References

- parent — _bmad-output/initiative-musicboxd/epic-catalogo-busca/epic-catalogo-busca.md
- ARCHITECTURE-SPINE.md#ad-1
- ARCHITECTURE-SPINE.md#stack

## Notes

- Depends on epic-contas-acesso entry 1 (2.1), which stands up the `profiles` username stub per the username-ownership decision — not on epic-perfil-favoritos, which only extends that schema later.
