---
tracker_id: "MBD-21"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-21"
tracker_status: "backlog"
id: 5
type: story
title: "STAFF role claim and admin route protection"
parent: epic-contas-acesso
after: [1]
hitl: true
risk: high
---

# STAFF role claim and admin route protection

## Description

Adds a role claim (USER default, STAFF assignable) to the access token and enforces it: every /api/v1/admin/** route requires STAFF, every other write requires an authenticated USER or STAFF, and public GETs need no token.

## Acceptance Criteria

Verify: A USER token gets 403 on every /api/v1/admin/** route; a STAFF token succeeds; an unauthenticated caller is rejected on any write and allowed on public reads.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md
- ARCHITECTURE-SPINE.md#ad-8

## Notes

- High risk check: a person confirms 403 on a sample of admin routes, not just one, before merge.
- Assumption: the first STAFF account is bootstrapped by a Flyway seed migration promoting an email read from an environment variable — no self-service staff promotion exists in the MVP. A person sets that variable on first production deploy (hitl).
