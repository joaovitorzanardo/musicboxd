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

Adds the accounts schema (Flyway), a registration endpoint (email/password, hashed with BCrypt/Argon2), a login endpoint issuing a ~15-minute JWT access token, and one demo endpoint that requires a valid token. Registration also stands up a minimal `profiles` schema stub (id, username) in the same transaction, since AD-13 assigns username ownership to `profiles`, not `accounts` — epic-perfil-favoritos later extends this same table with avatar, cover, bio, genres, and favorites; it never recreates it.

## Acceptance Criteria

Verify: A new person registers with an email, password, and username, logs in, gets a token, and calls the protected demo endpoint successfully; a wrong password is rejected; the username is readable from the `profiles` schema, not `accounts`.

## References

- parent — _bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md
- ARCHITECTURE-SPINE.md#ad-8
- ARCHITECTURE-SPINE.md#ad-13

## Notes

- High risk check: a person manually verifies a wrong password is rejected and a correct one issues a working token, before merge.
- Decision: password minimum 8 characters; username 3-20 chars, alphanumeric plus underscore (user, 2026-09-27).
- Decision: username ownership stays with `profiles` per AD-13, literal; this ticket stands up that schema's minimal stub (id, username) since it's needed at signup, ahead of epic-perfil-favoritos which builds the rest of `profiles` (user, 2026-09-27).
