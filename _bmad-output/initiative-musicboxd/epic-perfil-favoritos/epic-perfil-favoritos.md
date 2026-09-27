---
tracker_id: ""
key: ""
type: epic
title: "Profile and favorites"
parent: initiative-musicboxd
covers: [CAP-11, CAP-12, CAP-13]
after: []
assignee: ""
risk: medium
estimate: ""
estimate_basis: "envelope"
---

# Profile and favorites

## Description

The `profiles` module: display identity (picture, cover, bio), favorite genres from the Staff-managed list, 5+5 ordered Favorites, and the public Profile page assembled read-only by `feed` from `ratings`, `reviews`, `social`, and `catalog`. This is the public face of a User's taste, visible to Guests.

## Outcome

Anyone with a Profile link, logged in or not, sees the picture, cover, bio, genres, Favorites in the User's chosen order, and that User's recent Ratings and Reviews — with no ability to act unless they sign up.

## Requirements

- CAP-11 — set profile picture, cover picture, bio, and favorite genres from the Staff-managed list (`SPEC.md#capabilities`)
- CAP-12 — pick 5 favorite Albums and 5 favorite Songs in a chosen order; items need not be Rated; Songs may come from Albums outside the favorite Albums (`SPEC.md#capabilities`)
- CAP-13 — anyone, including Guests, views a Profile with picture, cover, bio, genres, Favorites, and recent Ratings/Reviews; sign-up prompted only on an attempted action (`SPEC.md#capabilities`)

## Done when

1. A User uploads a profile picture and cover image (via the `uploads` presign flow from epic-catalogo-busca) and sets a bio; all three appear on their Profile.
2. A User selects favorite genres only from the Staff-managed list.
3. A User sets 5 favorite Albums and 5 favorite Songs in a specific order, including unrated items and Songs from Albums not in their favorites; the Profile shows them in that order.
4. A logged-out visitor opens any Profile link and sees the full page — picture, cover, bio, genres, Favorites, recent Ratings and Reviews — with no login wall; any action attempt prompts sign-up.
5. The Profile's recent-activity section is assembled by `feed`'s read-only cross-schema query (AD-3), not written by `profiles` itself.
6. Deployed to production behind the epic-plataforma-base runtime.

## Boundaries

Owns the `profiles` schema (display identity, favorites, genre selection ids) but not the recent-Ratings/Reviews it displays — that's `feed` reading `ratings`/`reviews` read-only (AD-3), built here as part of "public Profile view" since CAP-13 has no other owner. Does not own the genre list itself (Staff-managed in `catalog`, epic-catalogo-busca) or follower/following counts (`social`, epic-social-feed) — those are shown together with this epic's fields on the same page but not written here.

- Touch point: `uploads`/S3 — reuses the presign contract epic-catalogo-busca introduced, unchanged, for avatar and cover images.

## References

- spec — `../../specs/spec-musicboxd/SPEC.md`, section Capabilities (CAP-11–CAP-13)
- architecture — `../../planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`, sections AD-3 (feed read-only assembly), AD-9 (image upload), AD-13 (profile identity, favorites, genres)
- ux — `../../planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md`, flow "Visitante chega por um link de perfil" (UJ-4)

## Notes

- Waits on epic-catalogo-busca because: the `uploads` presign flow for images is built there first.
- Waits on epic-ciclo-avaliacao because: the public Profile view (CAP-13) shows a User's Ratings and Reviews, which only exist once that epic ships.
