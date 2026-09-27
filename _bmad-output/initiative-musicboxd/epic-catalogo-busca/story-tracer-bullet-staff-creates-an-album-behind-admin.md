---
tracker_id: "MBD-28"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-28"
tracker_status: "backlog"
id: 1
type: story
title: "Tracer bullet: Staff creates an Album behind /admin"
parent: epic-catalogo-busca
after: [2.5]
risk: medium
---

# Tracer bullet: Staff creates an Album behind /admin

## Description

Adds the catalog schema (Flyway: albums, songs, genres) and a Staff-only endpoint to create an Album, gated by the STAFF role claim from epic-contas-acesso.

## Acceptance Criteria

Verify: A STAFF token creates an Album; a USER token gets 403 on the same endpoint.

## References

- parent — _bmad-output/initiative-musicboxd/epic-catalogo-busca/epic-catalogo-busca.md
- ARCHITECTURE-SPINE.md#ad-12
