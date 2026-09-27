---
tracker_id: "MBD-24"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-24"
tracker_status: "backlog"
id: 8
type: story
title: "SES production access and domain verification"
parent: epic-contas-acesso
hitl: true
risk: low
---

# SES production access and domain verification

## Description

Applies for Amazon SES production access (sandbox exit) and sets SPF and DKIM records on the sending domain.

## Acceptance Criteria

Verify: SES sends verification email to an arbitrary recipient address, not only pre-verified sandbox addresses.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md

## Notes

- Open question (from the epic): SES sandbox exit timing is outside the team's control; entry 2 works against sandbox-verified addresses until this lands.
