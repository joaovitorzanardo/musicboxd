# Adversary Review — ARCHITECTURE-SPINE (Musicboxd)

Method: for each hole, two parallel implementers (module/story/SPA/API) each follow every AD literally, and the result still does not fit together. Each hole ends with the AD to add or tighten.

Verdict: NOT ready as a build substrate. The module split and the AD-1 ownership rule are sound. But the spine says who owns tables and does not say who owns the shared shapes, the lifecycle of deleted things, or the failure semantics of the two mechanisms everything hangs on (events, refresh rotation). There are 12 holes. H1-H5 will cause real rework if left open.

---

## H1 (critical) — Feed cannot be built from the schemas AD-3 allows

- AD-3: `feed` reads `social`, `ratings`, `reviews`, `profiles` only, with SELECT-only grants. FR-10 needs each feed item to show album/song title and cover (catalog) and the actor's name and avatar.
- Story A (feed): obeys AD-3, joins the four schemas, and renders items with ids only. Or it obeys AD-1 and calls `catalog`'s facade per row (N+1). The diagram has no `feed -> catalog` or `feed -> accounts` edge, so either choice is an undeclared deviation.
- Story B (ratings): denormalizes `album_title`/`cover_key` into `ratings` rows so the feed can work. Now the catalog has two owners of a title, and Staff edits (FR-3) leave stale copies.
- Also: who owns the username? `accounts` (login) or `profiles` (display)? The diagram has `accounts -> profiles`. Feed reads `profiles`; the SPA author of a comment/review UI reads accounts. Both are "correct".
- Fix: tighten AD-3 to list `catalog` and the identity-bearing schema explicitly (or declare a projection view per module that is the only thing feed may SELECT). Add a "display identity" AD: `profiles` owns username, display name and avatar key; `accounts` owns only credentials/email/role.

## H2 (critical) — Deleting catalog entities with ratings/reviews/listenlist/favorites

- AD-6 says "deletion everywhere is a real DELETE" and FR-3 says Staff can "create and edit" only. Neither AD nor FR says whether Staff can delete an Album or Song. The Capability Map still credits FR-3 to AD-6.
- Pair: `catalog` ships `DELETE /admin/albums/{id}` with a plain delete. `ratings` migration (own schema, own Flyway set, per Migrations convention) declares `album_id uuid NOT NULL` with no FK, because AD-1 forbids cross-schema table coupling. Result: orphaned ratings, reviews, listenlist rows, and profile favorites; feed JOINs silently drop them or 500 on missing cover.
- Alternative pair: ratings declares a cross-schema FK `ON DELETE CASCADE`. Now `catalog`'s delete writes another module's tables, violating the "only writer" half of AD-1, and a Staff click erases every user's rating history with no event, so `listenlist`/`profiles` never learn of it.
- Song variant: FR-3 lets Staff reassign a Song to another Album. If `ratings` stored `album_id` on song_rating, it is now stale; if not, "user rated a song of an album they never logged" changes meaning silently.
- Users: no account-deletion path is defined, so `DELETE user` (AD-6) is another undefined cascade.
- Fix: new AD "Referential lifecycle". Recommended: catalog entities are never hard-deleted in the MVP (Staff may only edit; wrong entries are corrected in place). If deletion is required, `catalog` publishes `AlbumDeleted`/`SongDeleted` events and every dependent module deletes its own rows in the same transaction (uses AD-2), and cross-schema FKs are forbidden. State which of these; state the identical rule for user deletion and for Song-to-Album reassignment (song ratings keyed by song only; no `album_id` copy).

## H3 (critical) — AD-2 listener failure semantics are unspecified

- AD-2: listeners run inside the originating transaction. Not stated: (a) synchronous `@EventListener` vs `@TransactionalEventListener(BEFORE_COMMIT)`; (b) whether a listener exception aborts the publisher.
- Pair: `ratings` dev assumes a Listenlist failure never blocks rating (catches and logs at the publishing site or expects listener isolation). `listenlist` dev implements the listener as `REQUIRES_NEW` with a try/catch so it "never breaks callers". Both fine. Net result: the album is logged and still on the Listenlist, violating FR-8 and the explicit "same commit" promise; or the reverse, where a deleted-row deadlock rolls back the rating.
- Also: event fires on every upsert. Rating an album a second time (edit in place) republishes; listenlist must be idempotent, and an album re-added to the Listenlist after logging (FR-8 does not forbid it) is now inconsistent forever. `listenlist` cannot check `ratings` (no edge listenlist -> ratings; the only edge is the event). Adds are unguarded.
- Also: the event class lives in which package? If in `ratings`, `listenlist` imports `ratings` (a dependency arrow the diagram draws the opposite way). If in a shared kernel, that is a module the spine does not list.
- Also: there is no way to un-log (AD-4 says updated in place, never deleted; the Log is the rating; rating 0 is valid). A user who mis-logs cannot correct it; one team adds `DELETE /ratings/{id}`, the other never handles an "album unrated" event.
- Fix: tighten AD-2: listeners are synchronous, in the publisher's transaction, propagation REQUIRED, and any exception rolls back the publisher (fail-closed); listeners must be idempotent; events are published only on create, not update, or state so; event types live in the publisher's `api.events` package, and listener modules may depend on the publisher's events package (draw the edge). Tighten AD-4: state whether a rating can be deleted (and then `AlbumUnrated` event) and whether adding an already-logged album to the Listenlist is rejected by `listenlist` via a local mirror table populated by the event.

## H4 (critical) — Refresh-token rotation races between SPA and API

- AD-8: rotate on every use with reuse detection; access token in memory; refresh in a cookie.
- Pair: API dev implements strict reuse detection: presenting a rotated-out token revokes the whole family. SPA dev (generated OpenAPI client, AD-7) uses an axios/fetch interceptor that refreshes on 401. Two tabs, or three parallel requests after the 15-minute expiry, each send the same cookie. The second one is "reuse", the family is revoked, the user is logged out at random. Page reload also discards the in-memory access token and needs a refresh at load, which collides with StrictMode double effects in React 19 dev builds.
- Response loss: network drops after the server rotates; the client retries with the old cookie and trips reuse detection.
- Also unspecified: does refresh return the new refresh token only via Set-Cookie and the access token in the body; cookie `Path` (limited to `/api/v1/auth/refresh`?); CSRF posture of the refresh/logout endpoints (SameSite alone; `Lax` allows top-level GET only, but which value?); what logout revokes (one token or the family); whether a STAFF demotion takes effect within 15 minutes (role rides as a claim), and whether unverified accounts can obtain tokens.
- Fix: new/tightened AD-8 clause: grace window (e.g. 10 s) during which the just-rotated token is accepted once and returns the same successor, or an explicit "single-flight refresh in the SPA plus BroadcastChannel/Web Locks across tabs" rule (pick both). Specify cookie `Path`, `SameSite=Strict|Lax`, family revocation scope on logout and on reuse, role-change latency bound, and that unverified accounts cannot log in.

## H5 (high) — Who assembles the Public Profile and who owns follower/following counts

- FR-14 maps to `profiles` and `feed`. `profiles` needs counts (FR-9 "see who follows them"). `social` owns follows. AD-1 allows `profiles -> social` only via a facade, and the diagram has no such edge.
- Pair 1: `profiles` adds `follower_count`/`following_count` columns and updates them via listeners on `Followed`/`Unfollowed` events (AD-2 encourages this). `social` also exposes `GET /users/{id}/followers?count` computed from its table. Two owners of one number; they diverge on the first missed event or on an unfollow race.
- Pair 2: `profiles` controller returns the profile with picture, bio, favorites; the SPA calls `/feed`-owned recent-activity endpoint separately (the SPA composes), while the backend team assumes `profiles` composes recent activity by calling `feed`. `feed -> profiles` (AD-3 reads the profiles schema) plus `profiles -> feed` is a cycle that AD-1 bans.
- Fix: new AD "Derived counts and composed views": counts are never stored; `social` exposes `countFollowers/countFollowing`; there is no denormalized counter without a separate AD. Public Profile is an aggregate owned by exactly one endpoint. Recommended: `feed` (renamed `views`/`read`) owns all cross-module read composition including the profile page; `profiles` owns only its own writes and simple reads. Draw the edges.

## H6 (high) — Rating shape: the conversion boundary bypassed by feed

- AD-5: stored integer 0..10, API carries 0..5 in 0.5 steps, "conversion happens only in the API layer". AD-3: `feed` runs raw SQL on `ratings`.
- Pair: `ratings` controller converts (int/2.0). `feed`'s SQL returns the raw integer `rating` column and its DTO serializes 9 where ratings emits 4.5. The generated TS client types both as `number`; the UI shows "9 stars" in feed and 4.5 on the album page. Both obeyed AD-5 as written, since each module has its own "API layer".
- Also: a JSON number 4.5 is fine, but `4.0` vs `4` serialization and the `0` rating vs "no rating" (null) are not pinned; the OpenAPI type is `double`, allowing 4.25 through until validation.
- Fix: tighten AD-5: one shared `RatingScale` type in a shared-kernel package is the only conversion path, used by every controller including `feed`; OpenAPI schema for ratings is a single shared component (`Rating`, multipleOf 0.5, min 0, max 5); "no rating" is absence/null, never 0.

## H7 (high) — Uploads: who stores the key

- AD-9: client asks `uploads` for a presigned PUT, uploads, then "confirms so the API stores the object key". Purposes: avatar, cover, album art.
- Pair: `uploads` confirm handler writes `avatar_key` into `profiles` (breaks AD-1's sole-writer) or reaches into `catalog` for album art. Alternatively `profiles` `PUT /profile` accepts an arbitrary `avatarKey` string and calls `uploads.verifyIssued(key,user,purpose)`. That works, but `profiles -> uploads` and `catalog -> uploads` are edges the diagram lacks, and the confirm step is then redundant. Nobody owns cleanup of the replaced object (old avatar stays public in S3 forever) or of unconfirmed uploads.
- Also: public-read image prefix plus "keys it issued to that user" means a user can attach another user's cover key as their own, and can attach a Staff-purpose album-art key only if purpose is checked, which is not stated for the receiving module.
- Fix: tighten AD-9: `uploads` only issues and validates keys and exposes `consume(key, userId, purpose)` returning valid/invalid once; owning modules (`profiles`, `catalog`) call it when they save the key and are the only writers of the key column. Add edges. Add S3 lifecycle rule for unconfirmed/replaced keys. Album art purpose requires STAFF.

## H8 (high) — Favorites vs ratings vs catalog integrity

- FR-13 favorites live in `profiles` and reference album/song ids; UJ-2 says "his top songs feed his profile favorites", implying a relation to `ratings`.
- Pair: `profiles` validates that the ids exist through `catalog` facade (edge missing in diagram) or does not validate at all. Another dev enforces "favorite songs must be rated by the user" through `ratings` (edge missing), while a third reads UJ-2 as auto-population. Also unspecified: exactly 5 or up to 5, ordered or a set, replacement semantics (PUT whole list vs PATCH single slot), duplicates.
- Deleted album: favorite dangling (see H2).
- Fix: new AD: favorites are explicit user-chosen ordered lists of at most 5 per kind, replaced atomically by one `PUT`, validated against `catalog` (existence only); no coupling to ratings; draw `profiles -> catalog`.

## H9 (medium) — Cursor pagination over a mutable, multi-source, updated-in-place feed

- Ratings are updated in place (AD-4, no history). Feed orders by "newest". Pair: `feed` orders by `created_at`; `ratings` recent list orders by `updated_at`. An edited rating either does not resurface or duplicates across pages. Feed merges album ratings and song ratings from two tables; cursor shape (timestamp + tiebreaker + source table) is unspecified, so two implementers produce incompatible opaque cursors and the SPA generic hook breaks.
- Fix: tighten Pagination: define the sort key (`updated_at` of the rating, or a stable `logged_at`), tiebreaker `(timestamp, id)`, an opaque base64 cursor encoded by one shared utility, response envelope `{items, nextCursor}`.

## H10 (medium) — Admin surface has no module owner

- AD-7: `/api/v1/admin/**` requires STAFF. AD-1: controllers are per module. AD-6: Staff removes content with a real DELETE, but "only its author edits or deletes" a review.
- Pair: `catalog` owns `/admin/albums`. For content removal, a team puts `/admin/reviews/{id}` into `reviews`; another builds a generic `admin` package that runs DELETEs directly on `reviews`/`ratings` schemas (violates AD-1). Also FR-3's SPA `/admin` gets one endpoint shape from each.
- Fix: tighten AD-7: `/admin/<resource>` routes live in the owning module's controller package; no `admin` module. Staff removal is a second command on the same service (author check replaced by role check) and fires the same deletion events as author deletion. State whether Staff can remove ratings or only reviews.

## H11 (medium) — User discovery and identity keys missing

- FR-9 needs finding a user to follow; FR-4 covers only albums and songs. Which module hosts user search (`accounts` owns email/username? `profiles`?). Public profile URL key: opaque UUID (Conventions) vs username (UJ-4 shared link). Profile row id vs user id: same UUID, or two ids? `accounts -> profiles` on signup: who creates the profile row, in which transaction, and what happens if profile creation fails after account creation.
- Fix: AD "Identity": profile id == user id; `profiles` owns handle/display name and user search; profile row is created by a `UserRegistered` event (fail-closed per H3); public links use a unique handle.

## H12 (low) — Conventions gaps that generate clashes

- Problem Details `type` URIs and validation-error field format are not fixed; the generated client cannot type errors uniformly.
- Flyway: one migration set per schema, but `feed`'s SELECT grants span four sets; no owner or ordering for those grants and the shared read-only role.
- Rate limits keyed per-user for registration (AD-10) have no user yet; key is IP or email; unspecified.
- Email verification: token owner, expiry, resend limit not specified; JWT claims (`sub`, `roles`, `email_verified`) not fixed, so SPA and API can disagree.
- Fix: one line each in Consistency Conventions.

---

## Priority for the spine author

1. H2 referential lifecycle AD (deletion and reassignment).
2. H3 event failure semantics AD-2 tightening, plus event ownership and edges.
3. H1 + H5 read-composition and identity ownership (feed reads, profile aggregate, counts).
4. H4 refresh rotation grace/single-flight clause.
5. H6 shared rating type, then H7, H8, H9-H12.
