---
tracker_id: "MBD-23"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-23"
tracker_status: "backlog"
id: 7
type: story
title: "Per-user rate limits on login, registration, and verification email"
parent: epic-contas-acesso
after: [1, 2, 1.9]
risk: medium
---

# Per-user rate limits on login, registration, and verification email

## Description

Applies the platform's generic per-user rate limiter (epic-plataforma-base) to the login, registration, and verification-email-sending endpoints.

## Acceptance Criteria

Verify: Repeated login attempts, repeated registrations, and repeated verification-email requests from one account are each throttled past their configured limit.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md
- ARCHITECTURE-SPINE.md#ad-10
