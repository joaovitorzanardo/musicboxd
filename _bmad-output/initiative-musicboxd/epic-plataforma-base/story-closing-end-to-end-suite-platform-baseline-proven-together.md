---
tracker_id: "MBD-15"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-15"
tracker_status: "backlog"
id: 11
type: story
title: "Closing end-to-end suite: platform baseline proven together"
parent: epic-plataforma-base
after: [10]
risk: low
---

# Closing end-to-end suite: platform baseline proven together

## Description

One end-to-end suite that proves the whole platform baseline together: SPA reaches the API through nginx TLS on the EC2 host, Postgres backs up and restores, and budgets and rate limits are live.

## Acceptance Criteria

Verify: The suite runs against the deployed production stack and passes every Done when check in one pass.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md

## Notes

- Closing e2e suite offered per ordering rule and approved by user, 2026-09-27 — the SPEC has no formal test plan for this epic.
