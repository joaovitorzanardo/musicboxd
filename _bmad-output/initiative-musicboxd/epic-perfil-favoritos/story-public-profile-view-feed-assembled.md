---
tracker_id: "MBD-50"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-50"
tracker_status: "backlog"
id: 4
type: story
title: "Public Profile view (feed-assembled)"
parent: epic-perfil-favoritos
after: [1, 2, 3, 4.1, 4.4]
risk: medium
---

# Public Profile view (feed-assembled)

## Description

Introduces the `feed` module's read-only cross-schema query (AD-3) assembling a Profile: identity and favorites from entries 1-3, plus the User's recent Ratings and Reviews read directly from `ratings`/`reviews`. No token required; any write attempt on the page prompts sign-up instead.

## Acceptance Criteria

Verify: A logged-out visitor opens a Profile link and sees picture, cover, bio, genres, Favorites in order, and recent Ratings/Reviews with review text where present, newest first; no login wall blocks the read.

## References

- parent — _bmad-output/initiative-musicboxd/epic-perfil-favoritos/epic-perfil-favoritos.md
- ARCHITECTURE-SPINE.md#ad-3

## Notes

- Introduces the `feed` module and its read-only DB role (SELECT on profiles/ratings/reviews/catalog) — epic-social-feed extends this same module for the Feed itself and follower counts, never recreates it.
