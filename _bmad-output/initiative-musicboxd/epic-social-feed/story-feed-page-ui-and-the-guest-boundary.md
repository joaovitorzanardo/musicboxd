---
tracker_id: "MBD-60"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-60"
tracker_status: "backlog"
id: 3
type: story
title: "Feed page UI, and the Guest boundary"
parent: epic-social-feed
after: [2]
risk: medium
---

# Feed page UI, and the Guest boundary

## Description

Builds the Feed screen (per feed.html) wiring entry 2's query into the SPA with infinite/cursor scroll; a logged-out visitor sees only a message inviting sign-up and follow, with no Rating items, instead of an error or an empty-looking feed — the API route itself also requires authentication.

## Acceptance Criteria

Verify: A logged-in User scrolls their Feed, seeing followed Users' Ratings and Reviews newest first, loading more via the cursor with no direct API call; a logged-out visitor at the Feed route sees the sign-up/follow message and zero Rating items; the underlying API call is never made unauthenticated, or is rejected cleanly if it is.

## References

- parent — _bmad-output/initiative-musicboxd/epic-social-feed/epic-social-feed.md
- ../../planning-artifacts/ux-designs/ux-teste-2026-09-26/mockups/feed.html
