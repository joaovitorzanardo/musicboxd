---
tracker_id: "MBD-40"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-40"
tracker_status: "backlog"
id: 3
type: story
title: "Rate individual Songs, independent of the Album Score"
parent: epic-ciclo-avaliacao
after: [1, 3.2]
risk: medium
---

# Rate individual Songs, independent of the Album Score

## Description

Adds song_rating (user+song unique, same 0..10 representation, with a `created_at` bumped to now on every update, matching entry 1's rule) with its own create/update/delete endpoints, entirely independent of whether the Album has a Score.

## Acceptance Criteria

Verify: A User rates a Song with no Album Score set, and the Album stays un-Logged; the Album's own Score field, read alongside this, is exactly what entry 1 set — never a value derived from Song Ratings; updating an existing Song Rating sets a new `created_at`.

## References

- parent — _bmad-output/initiative-musicboxd/epic-ciclo-avaliacao/epic-ciclo-avaliacao.md
- ARCHITECTURE-SPINE.md#ad-4
- ARCHITECTURE-SPINE.md#ad-3

## Notes

- Decision: `created_at` bumps on update, matching entry 1 — needed for epic-social-feed's Feed ordering (AD-3), 2026-09-27.
