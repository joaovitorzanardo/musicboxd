---
title: Musicboxd
status: final
created: 2026-09-25
updated: 2026-09-25
---

# PRD: Musicboxd

_Working title — confirm._

## 0. Document Purpose

This PRD defines the MVP of Musicboxd for the builder and for downstream workflows (UX, architecture, epics). It builds on the brainstorming session in `_bmad-output/brainstorming/brainstorm-music-letterboxd-2026-09-25/brainstorm-intent.md`; that file remains the source for SHOULD/COULD detail, which is summarized in §6.2 and kept in full in `addendum.md`. Vocabulary is anchored in the Glossary (§3). Features group FRs with global IDs; inferred points are tagged `[ASSUMPTION]` and indexed in §9. Technical choices (data sources, APIs, stack) are out of scope and deferred to a separate technical session.

## 1. Vision

Musicboxd is a music tracker and social network for music, in the spirit of Letterboxd. People log the albums and songs they listen to, rate them, write reviews, and follow friends to see what they are listening to.

It is for music geeks who want a place to express their taste and passion, share it, and check in on friends. Artists are a secondary audience: they can see how people rated their work, exactly as any other user would.

It is built for fun, so competing products don't matter. The bar is a product that works and that its builder and friends can use day to day.

## 2. Target User

### 2.1 Jobs To Be Done

- Keep a personal record of the music I've listened to and how I felt about it.
- Express my taste: rate, review, and pick my all-time favorite albums and songs.
- Check in on what my friends are listening to and what they think of it.
- Keep a list of albums I want to listen to later.
- (Artist) See how people rated my work.

### 2.2 Non-Users (v1)

Casual listeners who don't care about logging. Artists needing special tools: there are no artist accounts.

### 2.3 Key User Journeys

- **UJ-1.** Marina, a music geek, finishes an album on her commute, opens Musicboxd, finds it, gives it 4.5 stars, and writes a short review; it then appears on her profile and in her friends' feeds.
- **UJ-2.** Diego gives an album 4 stars, then also rates each of its songs to mark the standouts; the album page shows only his 4 stars, and his top songs feed his profile favorites.
- **UJ-3.** Tiago follows Marina, opens his feed, sees her recent ratings, and adds an album she loved to his listenlist.
- **UJ-4.** A friend of a friend follows a link to Marina's profile without an account, reads her reviews and favorites, and signs up when they want to rate something themselves.

## 3. Glossary

- **User** — a person with an account. Can be followed and can follow other Users.
- **Guest** — a visitor without an account. Read-only.
- **Staff** — a privileged User role that manages the Catalog and can remove content.
- **Catalog** — the set of Albums and Songs available to log. Filled manually by Staff.
- **Album** — a release in the Catalog; contains one or more Songs.
- **Song** — a track belonging to an Album.
- **Log** — a User's record of having listened to an Album.
- **Rating** — a score from 0 to 5 in half-star steps, given to an Album or a Song.
- **Album Score** — the Rating a User gives an Album directly. Independent of that User's Song Ratings.
- **Review** — free-text opinion written by a User on an Album.
- **Listenlist** — a User's list of Albums they want to listen to.
- **Follow** — a directed link from one User to another.
- **Feed** — the User's stream of friends' recent Ratings.
- **Profile** — a User's public page.
- **Favorites** — a User's 5 all-time favorite Albums and 5 all-time favorite Songs.

## 4. Features

### 4.1 Accounts and Access

**Description:** Guests can browse and read Profiles, Reviews, and Albums without an account. An account is required for anything persistent: creating a Profile, Rating, Reviewing, Following, and using a Listenlist. Realizes UJ-4.

**Functional Requirements:**

#### FR-1: Guest browsing

A Guest can view Profiles, Reviews, Albums, and Songs.

- A Guest cannot Rate, Review, Follow, Log, or edit a Listenlist; attempting to do so prompts sign-up or login.

#### FR-2: Account creation and login

A person can create an account, log in, and log out. `[ASSUMPTION: authentication method (email/password vs social) is a technical-session decision]`

### 4.2 Catalog

**Description:** The Catalog is filled manually by Staff. Users cannot add to it in the MVP.

#### FR-3: Staff catalog management

Staff can create and edit Albums and Songs, and assign Songs to Albums.

- A non-Staff User cannot access catalog management.

#### FR-4: Catalog search

A User or Guest can search the Catalog for Albums and Songs and open their pages.

### 4.3 Logging and Rating

**Description:** The core loop. A User Logs an Album and Rates it in one of two modes: rate just the Album, or rate each Song. Realizes UJ-1, UJ-2. A User can Rate an Album, its Songs individually, or both. An Album is Logged only once per User; the User can change its Rating afterwards.

#### FR-5: Log and rate an Album

A User can Log an Album and give it a Rating from 0 to 5 in half-star steps.

- Values outside 0–5 or not on a 0.5 step are rejected.

#### FR-6: Rate individual Songs

A User can Rate individual Songs of an Album on the same scale, independently of the Album Score.

- The Album Score is always the Rating the User gave the Album directly; Song Ratings can change.
- An Album's page shows only the given Album Score. No score computed from Song Ratings is shown.
- A User may Rate some Songs, all Songs, or none, whether or not they Rated the Album.

#### FR-7: Write a Review

A User can write a Review on an Album.

- A User can edit or delete their own Review.

### 4.4 Listenlist

#### FR-8: Manage Listenlist

A User can add an Album to their Listenlist and remove it.

- Logging an Album that is on the Listenlist removes it from the Listenlist.
- A Listenlist is visible to other Users and Guests.

### 4.5 Social

**Description:** Follow friends and see their recent activity. Realizes UJ-3.

#### FR-9: Follow and unfollow

A User can Follow and unfollow another User, and see who follows them and who they follow.

#### FR-10: Feed

A User's Feed shows recent Ratings (Albums and Songs) by Users they Follow, newest first.

- Ratings by Users they don't Follow do not appear.

### 4.6 Profile

**Description:** The public face of a User's taste. Visible to Guests. Realizes UJ-4.

#### FR-11: Profile content

A User can set a profile picture, cover picture, and bio.

#### FR-12: Favorite genres

A User can choose their own favorite genres. These are later used for recommendations.

#### FR-13: Favorites

A User can choose 5 favorite Albums and 5 favorite Songs, shown on their Profile.

- Favorite Songs may come from Albums not among the favorite Albums.

#### FR-14: Public Profile view

The Profile shows the picture, cover, bio, genres, Favorites, and the User's recent Ratings and Reviews to anyone, including Guests.

### 4.7 Cross-cutting NFRs

- **Usability:** the core loop (find album → Rate → optional Review) should feel quick in daily use. `[ASSUMPTION: no numeric target for a hobby project]`
- **Privacy:** all Profiles and Listenlists are public in the MVP.

## 5. Non-Goals (Explicit)

- No special artist accounts or artist-only views.
- No "because you rated X" explanations on recommendations.
- No Album Score computed from Song Ratings.
- No ratings-vs-plays analysis.
- No listening context tags (mood, place, first listen vs re-listen).
- No competition-driven features; this is built for fun.
- No moderation or report flow in the MVP.

## 6. MVP Scope

### 6.1 In Scope

Everything in §4: guest browsing and accounts, staff-managed Catalog, Album logging with 0–5 half-star Ratings, independent per-Song Ratings, Reviews, Listenlist, Follow, a friends' Feed, and a public Profile with Favorites and favorite genres.

### 6.2 Out of Scope for MVP

- **SHOULD (next):** Lists (public/private, followable); likes and replies on Reviews (flat vs nested undecided); following Artists with new releases at the top of the Feed; a "Request this album" button feeding a staff queue.
- **COULD (later):** black-box recommendations; streaming integration with monthly stats; automated Catalog imports via APIs and scrapers.
- Full detail in `addendum.md`.

`[NOTE FOR PM]` "Follow Artists + releases in Feed" (SHOULD) depends on release data that would come from automated imports (COULD); resolve in the technical session.

## 7. Success Metrics

**Primary**

- **SM-1**: The software is functional and usable in the builder's day-to-day life: the full core loop (FR-4 → FR-5/6 → FR-7) and the Feed work end to end without workarounds. Validates FR-1–FR-14.

No counter-metrics: a hobby project with no growth targets to over-optimize.

## 8. Open Questions

1. Which data sources and APIs feed the Catalog (licensing, cover art)? — technical session.
2. Flat or nested replies on Reviews? — needed when Review interaction is built.
3. How do "follow Artists" and release data work if automated imports come last?
4. Authentication method (email/password, social login)?

## 9. Assumptions Index

- §4.1 FR-2 — authentication method deferred to technical session.
- §4.7 — no numeric usability target.
- Staff is a privileged role for Catalog entry and content removal (no moderation flow).
