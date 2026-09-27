---
tracker_id: "MBD-19"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-19"
tracker_status: "backlog"
id: 3
type: story
title: "Refresh tokens: opaque, rotated, revocable"
parent: epic-contas-acesso
after: [1]
risk: high
---

# Refresh tokens: opaque, rotated, revocable

## Description

Issues an opaque refresh token at login, stored hashed in the DB, delivered in an HttpOnly/Secure/SameSite=Lax cookie scoped to the refresh path, rotated on every use with reuse detection and a ~10-second grace window.

## Acceptance Criteria

Verify: Refreshing extends the session with a new token and invalidates the old one; replaying an old refresh token after rotation is rejected; two refreshes within the grace window both succeed.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md
- ARCHITECTURE-SPINE.md#ad-8

## Notes

- High risk check: a person exercises concurrent refresh calls and confirms neither a false logout nor a security hole from the grace window.
