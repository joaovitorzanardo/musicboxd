---
tracker_id: "MBD-17"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-17"
tracker_status: "backlog"
id: 1
type: story
title: "Tracer bullet: register, login, and a protected endpoint"
parent: epic-contas-acesso
after: [1.1]
risk: high
---

# Tracer bullet: register, login, and a protected endpoint

## Description

Adds the accounts schema (Flyway), a registration endpoint (email/password, hashed with BCrypt/Argon2), a login endpoint issuing a ~15-minute JWT access token, and one demo endpoint that requires a valid token.

## Acceptance Criteria

Verify: A new person registers, logs in, gets a token, and calls the protected demo endpoint successfully; a wrong password is rejected.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md
- ARCHITECTURE-SPINE.md#ad-8

## Notes

- High risk check: a person manually verifies a wrong password is rejected and a correct one issues a working token, before merge.
- Decision: password minimum 8 characters; username 3-20 chars, alphanumeric plus underscore (user, 2026-09-27).
