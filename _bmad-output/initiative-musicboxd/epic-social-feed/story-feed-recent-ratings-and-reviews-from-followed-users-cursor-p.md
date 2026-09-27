---
tracker_id: "MBD-59"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-59"
tracker_status: "backlog"
id: 2
type: story
title: "Feed: recent Ratings and Reviews from followed Users, cursor-paginated"
parent: epic-social-feed
after: [1, 4.1, 4.3, 4.4, 5.4]
risk: medium
---

# Feed: recent Ratings and Reviews from followed Users, cursor-paginated

## Description

Extends the `feed` module (introduced in epic-perfil-favoritos entry 4) with the Feed query: a read-only, indexed cross-schema JOIN across `social`, `ratings`, `reviews` returning Album and Song Ratings (with Review text where one exists) from Users the caller follows, newest first, cursor-paginated on (created_at DESC, id DESC), computed at read time with no precomputed table. Extends the `feed` DB role's grants to include SELECT on the `social` schema this epic's entry 1 introduces.

## Acceptance Criteria

Verify: After Marina rates an Album, a follower's Feed shows it at the top; Ratings from unfollowed Users never appear; re-rating an already-Rated Album/Song (whose created_at just bumped) reappears at the top as new activity; paging by the cursor returns no duplicates and no gaps; the query plan shows index use on the cursor columns, not a sequential scan.

## References

- parent — _bmad-output/initiative-musicboxd/epic-social-feed/epic-social-feed.md
- ARCHITECTURE-SPINE.md#ad-3

## Notes

- Depends on epic-ciclo-avaliacao entries 1 and 3 carrying the created_at-bumps-on-update fix (2026-09-27) — without it, re-rating would not resurface in this Feed.
