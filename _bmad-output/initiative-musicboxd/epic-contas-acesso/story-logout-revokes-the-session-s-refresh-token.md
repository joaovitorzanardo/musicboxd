---
tracker_id: "MBD-20"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-20"
tracker_status: "backlog"
id: 4
type: story
title: "Logout revokes the session's refresh token"
parent: epic-contas-acesso
after: [3]
risk: high
---

# Logout revokes the session's refresh token

## Description

A logout endpoint revokes the caller's current refresh token so it can no longer be used to obtain new access tokens.

## Acceptance Criteria

Verify: After logout, the same refresh token is rejected; a fresh login still works.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md

## Notes

- High risk check: a person confirms a revoked refresh token is rejected, not merely expired-looking.
