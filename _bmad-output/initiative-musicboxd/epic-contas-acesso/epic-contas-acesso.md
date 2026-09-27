---
tracker_id: "MBD-16"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-16"
tracker_status: "backlog"
key: ""
type: epic
title: "Accounts and access"
parent: initiative-musicboxd
covers: [CAP-1, CAP-2]
after: []
assignee: ""
risk: medium
estimate: ""
estimate_basis: "envelope"
---

# Accounts and access

## Description

The `accounts` module: sign-up with email verification, login, logout, and the Guest/User boundary every other capability checks. Realizes the SPEC's CAP-1 and CAP-2, and seeds the USER/STAFF role claim that epic-catalogo-busca gates its admin area on.

## Outcome

A new person can sign up, verify their email, log in, and log out; a Guest can read everywhere but is prompted to sign up the moment they try to act (rate, review, follow, log, or touch a listenlist).

## Requirements

- CAP-1 — a Guest can read Profiles, Reviews, Albums, and Songs without an account; write actions prompt sign-up/login instead of acting (`SPEC.md#capabilities`)
- CAP-2 — a person can create an account, log in, and log out; a Guest can reach no persistent action without one (`SPEC.md#capabilities`)

## Done when

1. A new person signs up, receives a verification email (Amazon SES), verifies, and can then log in.
2. An unverified or logged-out visitor cannot rate, review, follow, log, or manage a listenlist — each attempt redirects to sign-up/login instead of silently failing.
3. Access tokens are short-lived JWTs; refresh tokens are opaque, hashed, rotated on use with reuse detection, and revoked on logout (AD-8).
4. Every public `GET` route works with no token; every write route rejects an unauthenticated caller.
5. Deployed to production behind the epic-plataforma-base runtime, with per-user rate limits on login, registration, and email sending live (AD-10).

## Boundaries

Owns the `accounts` Postgres schema and package only. Does not touch `profiles` (display identity, avatar/bio — epic-perfil-favoritos) beyond issuing the `USER`/`STAFF` role claim. Password recovery is explicitly deferred (AD-8, SPEC Assumptions) — out of scope here and for the MVP.

- Touch point: Amazon SES — needs production access (sandbox exit) with SPF/DKIM before verification email can go live in production; this epic owns getting SES ready, but the exit timing is outside the team's control (SPEC Open Questions).

## References

- spec — `../../specs/spec-musicboxd/SPEC.md`, section Capabilities (CAP-1, CAP-2) and Assumptions (auth method)
- architecture — `../../planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`, section AD-8 (Authentication and authorization), AD-10 (per-user rate limits)
- ux — `../../planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md`, flow "Visitante chega por um link de perfil"

## Notes

- Open question (from SPEC): SES sandbox exit timing with SPF/DKIM set — email verification cannot go fully live in production until this clears. Track and surface if it blocks the epic's Done when.
- Decision: password minimum 8 characters; username 3-20 chars, alphanumeric plus underscore (user, 2026-09-27) — settles the SPEC's open assumption before entry 1 starts.
- Decision: this epic owns AD-8's authentication mechanics (tokens, roles, route-level gating) but not AD-8's "every write verifies the resource belongs to the token's user" rule, since `accounts` owns no other module's writable resources. That rule is deferred to each resource-owning epic (epic-ciclo-avaliacao, epic-perfil-favoritos, epic-social-feed), each noted accordingly (set check finding, 2026-09-27).
