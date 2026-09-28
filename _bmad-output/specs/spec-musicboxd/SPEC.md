---
id: SPEC-musicboxd
companions:
  - glossary.md
  - roadmap.md
  - ../../planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md
  - ../../planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md
  - ../../planning-artifacts/ux-designs/ux-teste-2026-09-26/DESIGN.md
sources:
  - ../../planning-artifacts/prds/prd-teste-2026-09-25/prd.md
  - ../../planning-artifacts/prds/prd-teste-2026-09-25/addendum.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate. Source documents listed in frontmatter are for traceability — consult them only if you need narrative rationale or prose color this contract intentionally omits.

# Musicboxd

## Why

A vision to realize: a music tracker and social network in the spirit of Letterboxd. Music geeks want one place to log the albums and songs they hear, rate and review them, show their taste, and see what friends are listening to. Artists are a secondary audience who see ratings like anyone else. Built for fun; the bar is a product its builder and friends use day to day. Terms are defined in `glossary.md`.

## Capabilities

- **CAP-1**
  - **intent:** A Guest can read Profiles, Reviews, Albums, and Songs without an account.
  - **success:** Every read page loads logged out; Rate, Review, Follow, Log, and Listenlist actions prompt sign-up or login instead of acting.
- **CAP-2**
  - **intent:** A person can create an account, log in, and log out.
  - **success:** A new person signs up, logs in, and logs out; a Guest can reach no persistent action without an account.
- **CAP-3**
  - **intent:** Staff can create, edit, and hide Albums and Songs, assign Songs to Albums, and manage the genre list.
  - **success:** A hidden item is absent from search but opens by its link; a non-Staff User cannot reach catalog management; no delete exists.
- **CAP-4**
  - **intent:** Any visitor can search the Catalog for Albums and Songs, and Users by username, and open their pages.
  - **success:** A query returns matching non-hidden Albums and Songs and matching Users in a dropdown, each opening its page. There is no results page, pagination, or shareable search URL.
- **CAP-5**
  - **intent:** A User can Log an Album by giving it an Album Score from 0 to 5 in half-star steps, change it, or delete it.
  - **success:** Scores off the 0.5 step or outside 0–5 are rejected; deleting the score un-Logs the Album; an Album is Logged once per User.
- **CAP-6**
  - **intent:** A User can Rate individual Songs on the same scale, independently of the Album Score.
  - **success:** A Song can be Rated with no Album Score and the Album stays un-Logged; the Album page shows only the given Album Score, never a score derived from Song Ratings.
- **CAP-7**
  - **intent:** A User can write, edit, and delete their own Review of an Album.
  - **success:** The Review shows on the Album page and the User's Profile; no other User can edit or delete it.
- **CAP-8**
  - **intent:** A User can add Albums to a public Listenlist and remove them.
  - **success:** Logging a Listenlisted Album removes it from the Listenlist; an already-Logged Album cannot be added.
- **CAP-9**
  - **intent:** A User can Follow and unfollow other Users and see who follows them and whom they follow.
  - **success:** Follow state and both lists update immediately and match the counts shown.
- **CAP-10**
  - **intent:** A User sees a Feed of recent Album and Song Ratings, with the Review text where one exists, from Users they Follow, newest first. A Guest at the Feed route sees only a message inviting them to sign up and follow someone.
  - **success:** After Marina rates an album, a follower's Feed shows it at the top; Ratings from unfollowed Users never appear; an updated Rating counts as new activity; a Guest sees the message and no items.
- **CAP-11**
  - **intent:** A User can set a profile picture, cover picture, and bio, and choose favorite genres from the Staff-managed list.
  - **success:** Saved values appear on the Profile; only listed genres are selectable.
- **CAP-12**
  - **intent:** A User can pick 5 favorite Albums and 5 favorite Songs in a chosen order.
  - **success:** Favorites show in the set order; items need not be Rated; Songs may come from Albums outside the favorite Albums.
- **CAP-13**
  - **intent:** Anyone, including Guests, can view a Profile with picture, cover, bio, genres, Favorites, and the User's recent Ratings and Reviews.
  - **success:** A logged-out visitor following a Profile link sees all of it and is prompted to sign up only when trying to act.

## Constraints

- Ratings are 0–5 in 0.5 steps; an Album Score is only ever the score the User gave the Album directly.
- Albums and Songs are never deleted, so Ratings, Reviews, Listenlists, and Favorites stay valid.
- All Profiles and Listenlists are public.
- Only the Album Score Logs an Album; Song Ratings never do.
- The Catalog is filled by Staff only; Users cannot add to it.
- Behavior lives behind the `/api/v1` API so a future app reuses it; the architecture spine binds stack and module boundaries.
- UI is pt-BR per the experience spine; where it diverges from the PRD it wins for UX.

## Non-goals

- Artist accounts or artist-only views.
- "Because you rated X" explanations.
- An Album Score computed from Song Ratings.
- Ratings-vs-plays analysis.
- Listening context tags (mood, place, first vs re-listen).
- Competition-driven features.
- Moderation or report flow in the MVP, including Staff removal of Reviews.
- SHOULD/COULD items in `roadmap.md`.

## Success signal

The builder uses Musicboxd day to day: the full core loop (search → rate Album/Songs → optional Review) and the Feed work end to end with no workarounds.

## Assumptions

- Authentication is email/password with email verification at signup and no password recovery (architecture AD-8; the PRD left the method open).
- Password minimum of 8 and username charset rules are unconfirmed.
- No numeric usability target.

## Open Questions

- When does Amazon SES leave sandbox, with SPF/DKIM set, so email verification can go live?
- Catalog data source and cover-art licensing are deferred; the Catalog is manual until then.
- The architecture spine still says numbered pages for catalog search; this spec says dropdown-only. Reconcile before implementation.
