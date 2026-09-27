---
tracker_id: "MBD-58"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-58"
tracker_status: "backlog"
id: 1
type: story
title: "Tracer bullet: Follow and unfollow, with computed counts"
parent: epic-social-feed
after: [2.1, 5.7]
risk: medium
---

# Tracer bullet: Follow and unfollow, with computed counts

## Description

Adds the social schema (follow: follower_id/followee_id unique pair), endpoints to Follow and unfollow, and follower/following list and count queries — counts are always computed at read time, never stored (AD-13) — and adds the Follow/unfollow button and follower/following lists to the Profile page the SPA already renders (epic-perfil-favoritos), since that page is where this action lives.

## Acceptance Criteria

Verify: A User follows and unfollows another User via a button on their Profile page; both users' lists and counts update immediately and match, visible on their Profile pages with no direct API call; a caller can only remove their own follow edge, never another user's, even if a followee/follower id is supplied.

## References

- parent — _bmad-output/initiative-musicboxd/epic-social-feed/epic-social-feed.md
- ARCHITECTURE-SPINE.md#ad-13

## Notes

- Extends the Profile page UI (epic-perfil-favoritos entry 7) with the Follow button and lists — that epic's Boundaries already deferred this to avoid re-touching the page twice.
