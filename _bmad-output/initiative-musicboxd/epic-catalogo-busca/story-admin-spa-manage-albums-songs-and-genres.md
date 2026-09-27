---
tracker_id: "MBD-53"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-53"
tracker_status: "backlog"
id: 10
type: story
title: "Admin SPA: manage Albums, Songs, and genres"
parent: epic-catalogo-busca
after: [1, 2, 3, 5]
risk: medium
---

# Admin SPA: manage Albums, Songs, and genres

## Description

Builds the Staff-only /admin screens (per the admin.html mockup) wiring entries 1, 2, 3, and 5 into the SPA: create/edit/hide Albums and Songs, assign Songs, upload album art, and manage the genre list.

## Acceptance Criteria

Verify: Staff uses the /admin screens to create an Album, edit it, add a Song, hide it, upload its art, and manage the genre list — no direct API calls needed; a non-Staff User cannot reach /admin in the SPA at all.

## References

- parent — _bmad-output/initiative-musicboxd/epic-catalogo-busca/epic-catalogo-busca.md
- ../../planning-artifacts/ux-designs/ux-teste-2026-09-26/mockups/admin.html
