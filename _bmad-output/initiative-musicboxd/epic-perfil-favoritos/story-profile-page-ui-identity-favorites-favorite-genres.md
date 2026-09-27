---
tracker_id: "MBD-56"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-56"
tracker_status: "backlog"
id: 7
type: story
title: "Profile page UI: identity, favorites, favorite genres"
parent: epic-perfil-favoritos
after: [1, 2, 3, 4]
risk: medium
---

# Profile page UI: identity, favorites, favorite genres

## Description

Builds the Profile page UI (per the perfil.html mockup) wiring entries 1 (avatar/cover/bio), 2 (favorite genres), 3 (5+5 favorites), and 4 (public assembled view) into the SPA. epic-social-feed later extends this same page with the Follow button and lists, so this ticket leaves visible room for that but does not build it.

## Acceptance Criteria

Verify: A User edits their own avatar, cover, bio, favorite genres, and orders their 5+5 Favorites entirely through the SPA; a Guest opens any Profile URL and sees the full rendered page with no direct API call needed to inspect it.

## References

- parent — _bmad-output/initiative-musicboxd/epic-perfil-favoritos/epic-perfil-favoritos.md
- ../../planning-artifacts/ux-designs/ux-teste-2026-09-26/mockups/perfil.html
