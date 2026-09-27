---
tracker_id: ""
key: ""
type: epic
title: "Core loop: rate, review, listenlist"
parent: initiative-musicboxd
covers: [CAP-5, CAP-6, CAP-7, CAP-8]
after: []
assignee: ""
risk: high
estimate: ""
estimate_basis: "envelope"
---

# Core loop: rate, review, listenlist

## Description

The `ratings`, `reviews`, and `listenlist` modules together: the product's core loop. A User logs an Album by scoring it, rates Songs independently, writes a Review, and keeps a public Listenlist that reacts to logging via a domain event. This is the SPEC's own success signal's core (search → rate → optional review), so it carries the highest risk in the initiative.

## Outcome

A User can go from an unlogged Album to a scored, reviewed, Listenlist-aware state in one sitting, with the Album Score always meaning exactly what the User gave it directly — never a value derived from Song Ratings.

## Requirements

- CAP-5 — Log an Album via an Album Score 0–5 in half-star steps; change or delete it; scores off-step or out of range rejected; deleting un-Logs; one Log per User (`SPEC.md#capabilities`)
- CAP-6 — Rate individual Songs on the same scale, independent of the Album Score; Album page never shows a derived score (`SPEC.md#capabilities`)
- CAP-7 — write, edit, delete a User's own Review of an Album; visible on Album page and Profile; no other User can touch it (`SPEC.md#capabilities`)
- CAP-8 — add/remove Albums to a public Listenlist; logging a Listenlisted Album removes it; an already-Logged Album cannot be added (`SPEC.md#capabilities`)

## Done when

1. A User gives an Album a Score (0–5, 0.5 steps); an out-of-range or off-step value is rejected with a clear error; the Album is now Logged for that User.
2. Deleting that Album Score un-Logs the Album; the User can re-score it afterward.
3. A User rates individual Songs of an Album with no Album Score set, and the Album stays un-Logged; the Album page shows only the given Album Score, never one computed from Song Ratings.
4. A User writes, edits, and deletes their own Review; it appears on the Album page and their Profile; no other User's client can mutate it (server-enforced ownership).
5. Adding a Logged Album to the Listenlist is rejected; scoring a Listenlisted Album removes it from the Listenlist in the same commit (AD-2 domain event, in-process, fails closed).
6. Deployed to production; the Listenlist-removal event is demonstrably idempotent under a retried publish.

## Boundaries

Owns `ratings`, `reviews`, and `listenlist` schemas — three modules, one epic, because `listenlist` reacts to `ratings` via an in-process event and splitting them would put a single business rule (AD-2) across two owners. Does not touch `feed` (read-only aggregation, epic-social-feed) or `profiles` (epic-perfil-favoritos), though both later read this epic's tables through their own public APIs.

- Touch point: none new — reuses Albums/Songs from epic-catalogo-busca and the auth/ownership checks from epic-contas-acesso.

## References

- spec — `../../specs/spec-musicboxd/SPEC.md`, section Capabilities (CAP-5–CAP-8) and Constraints (rating scale, Album Score independence)
- architecture — `../../planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`, sections AD-2 (domain events), AD-4 (ratings own scores/log), AD-5 (rating representation), AD-6 (one review per user, hard deletes)
- ux — `../../planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md`, flows "Marina avalia e escreve review" (UJ-1), "Diego avalia o album e as faixas" (UJ-2)

## Notes

- Decision: this epic owns AD-8's "every write verifies the resource belongs to the token's user" for ratings, reviews, and listenlist items — deferred here from epic-contas-acesso, which owns only the auth mechanics (2026-09-27).
- Source conflict: SPEC lists Staff removal of Reviews as a non-goal for the MVP, overriding AD-6's admin-delete endpoint; see the initiative's Notes for the full decision. This epic builds only the owner's own edit/delete, no Staff endpoint.
