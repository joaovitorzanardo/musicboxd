---
tracker_id: ""
key: ""
type: epic
title: "Social: follow and feed"
parent: initiative-musicboxd
covers: [CAP-9, CAP-10]
after: []
assignee: ""
risk: medium
estimate: ""
estimate_basis: "envelope"
---

# Social: follow and feed

## Description

The `social` module (Follow/unfollow, follower/following lists and counts) and the read-only `feed` module's main job: a User's Feed of recent Album and Song Ratings, with Review text where it exists, from Users they follow, newest first. This is the last link in the initiative's tracer path.

## Outcome

Tiago follows Marina; after she rates an album, it appears at the top of his Feed, newest first, with her review text if she wrote one — and a Guest at the Feed route sees only a sign-up prompt, no items.

## Requirements

- CAP-9 — Follow and unfollow other Users; see who follows them and whom they follow; state and counts update immediately and match (`SPEC.md#capabilities`)
- CAP-10 — Feed of recent Album/Song Ratings (with Review text) from followed Users, newest first; unfollowed Users' Ratings never appear; an updated Rating counts as new activity; a Guest sees only a sign-up message (`SPEC.md#capabilities`)

## Done when

1. A User follows and unfollows another User; both users' follower/following lists and counts update immediately and consistently — counts are computed by `social`, never stored (AD-13).
2. After Marina rates an Album, a follower's Feed shows it at the top; Ratings from Users the viewer doesn't follow never appear.
3. Updating an existing Rating gives it a new `created_at` and it reappears at the top of the Feed as new activity.
4. Feed pagination uses a stable cursor (`created_at` DESC, `id` DESC); the Feed is computed at read time with an indexed JOIN, no precomputed table.
5. A Guest visiting the Feed route sees only a message inviting sign-up/follow, and no Rating items.
6. Deployed to production behind the epic-plataforma-base runtime.

## Boundaries

Owns the `social` schema (Follow edges) and the `feed` module's Feed query specifically (its Profile-assembly use was already scoped to epic-perfil-favoritos). `feed`'s DB role is read-only (`SELECT`) across `social`, `ratings`, `reviews`, `profiles`, `catalog`, `accounts` — it writes nothing (AD-3). No recommendations, no Follow-Artists, no release data — all roadmap SHOULD/COULD, explicitly out of scope.

- Touch point: none new — reads `ratings`/`reviews` (epic-ciclo-avaliacao) and `profiles` (epic-perfil-favoritos) through `feed`'s read-only cross-schema access.

## References

- spec — `../../specs/spec-musicboxd/SPEC.md`, section Capabilities (CAP-9, CAP-10)
- architecture — `../../planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`, sections AD-3 (feed read-only, cursor order), AD-13 (follower counts computed, not stored)
- ux — `../../planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md`, flow "Tiago segue Marina e salva um album" (UJ-3)
- roadmap — `../../specs/spec-musicboxd/roadmap.md`, Follow Artists (explicitly deferred, not this epic)

## Notes

- Decision: this epic owns AD-8's "every write verifies the resource belongs to the token's user" for Follow/unfollow edges — deferred here from epic-contas-acesso, which owns only the auth mechanics (2026-09-27).
- Waits on epic-ciclo-avaliacao because: the Feed has nothing to show until Ratings and Reviews exist.
- Waits on epic-perfil-favoritos because: the Follow lists and Feed surface alongside the Profile view that epic assembles; building social first would mean re-touching that page.
