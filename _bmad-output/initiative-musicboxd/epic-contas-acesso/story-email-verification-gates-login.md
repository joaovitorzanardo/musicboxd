---
tracker_id: "MBD-18"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-18"
tracker_status: "backlog"
id: 2
type: story
title: "Email verification gates login"
parent: epic-contas-acesso
after: [1]
risk: high
---

# Email verification gates login

## Description

Generates a one-time verification token at signup, emails it via Amazon SES, and rejects login for an unverified account until the verify endpoint consumes that token.

## Acceptance Criteria

Verify: A freshly registered, unverified account cannot log in; after visiting the verification link, the same credentials log in.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md
- ARCHITECTURE-SPINE.md#ad-8

## Notes

- High risk check: a person confirms an unverified account is truly blocked from login, not just from one code path, before merge.
