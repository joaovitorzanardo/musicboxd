---
tracker_id: "MBD-49"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-49"
tracker_status: "backlog"
id: 3
type: story
title: "5+5 ordered Favorites (Albums and Songs)"
parent: epic-perfil-favoritos
after: [3.1, 3.2]
risk: medium
---

# 5+5 ordered Favorites (Albums and Songs)

## Description

Adds ordered favorite slots (5 Albums, 5 Songs) referencing catalog ids by id only (no cross-schema foreign key, per AD-12), with no requirement that the item is Rated.

## Acceptance Criteria

Verify: A User sets 5 favorite Albums and 5 favorite Songs in a chosen order, including an unrated item and a Song from an Album outside the favorite Albums; the profile shows them in that exact order.

## References

- parent — _bmad-output/initiative-musicboxd/epic-perfil-favoritos/epic-perfil-favoritos.md
- ARCHITECTURE-SPINE.md#ad-12
- ARCHITECTURE-SPINE.md#ad-13
