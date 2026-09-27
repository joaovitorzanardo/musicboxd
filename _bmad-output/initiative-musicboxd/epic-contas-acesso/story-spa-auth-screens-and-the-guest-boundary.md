---
tracker_id: "MBD-22"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-22"
tracker_status: "backlog"
id: 6
type: story
title: "SPA auth screens and the Guest boundary"
parent: epic-contas-acesso
after: [1]
risk: medium
---

# SPA auth screens and the Guest boundary

## Description

Adds sign-up, login, and logout screens to the SPA, holds the access token in memory, and redirects any Guest whose write action gets a 401 to sign-up/login instead of failing silently.

## Acceptance Criteria

Verify: A logged-out visitor who tries a write action (once one exists) is redirected to sign-up; after logging in, in-memory token state survives navigation but not a hard refresh without a working refresh flow.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md
