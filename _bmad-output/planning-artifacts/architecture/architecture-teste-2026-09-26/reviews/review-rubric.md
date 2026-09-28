# Review (rubric) — ARCHITECTURE-SPINE.md vs PRD

Verdict: **REVISE** (solid base; several real divergence points are unfixed and a few Rules are not enforceable as written).

Severity: High = two units would plausibly diverge or the rule cannot be enforced; Medium = gap likely to cause rework; Low = polish.

## 1. Divergence points fixed / missed

**H1. Catalog deletion vs hard deletes and module ownership (AD-1, AD-6).**
AD-6 says all deletion, including Staff removal, is a real DELETE. FR-3 allows Staff to edit/create albums and songs, and the PRD says Staff can remove content. Deleting an album or song touches ratings, reviews, listenlist and profile favorites, all in other modules' schemas, and AD-1 forbids cross-module writes. Not decided: whether catalog deletion is allowed, whether it is blocked when referenced, or whether it cascades through domain events (AD-2). Cross-schema foreign keys are also undecided (user_id, album_id in every module): are there FKs across schemas, or opaque ids with event-driven cleanup? Two teams will choose differently.
Also AD-6 says "only its author edits or deletes" a review, while also saying Staff removal happens. There is no admin endpoint or owner module for removing reviews, ratings or profile images. Fix: add a rule for Staff content removal (endpoint, which module deletes) and for catalog delete semantics, plus an FK policy.

**H2. Data model gaps that the level below must invent.**
The ER diagram has no Artist, Genre, Favorite, Follow attributes, Profile or Staff/role entity. Undecided:
- Is artist a string or an entity? Genres (FR-12): free text, or a controlled list managed by Staff? Do albums carry genres?
- Favorites (FR-13): constraints (max 5 or exactly 5, ordered or not, per-kind), who owns them (`profiles` stores catalog ids, yet the diagram shows no profiles -> catalog edge).
- Follow: self-follow prohibited, uniqueness, and who owns follower counts.
- How the first STAFF user is created (bootstrap/seed vs admin promotion).
These are the classic parallel-implementation splits. Fix: add a short data ownership table or an AD for catalog shape, genres and favorites.

**H3. Rating/Log lifecycle edge cases (AD-4).**
"Logged iff has album rating" is clean, but unspecified:
- How to un-log (delete the rating) and what happens to a listenlist entry afterwards.
- Whether an already-logged album can be added to the Listenlist (FR-8 only defines the reverse).
- Whether a Review requires a logged album.
- Feed and Profile ordering: ratings are updated in place with no history, so which timestamp orders the Feed (created or updated)? Does an edited rating reappear at the top? FR-10 says "recent Ratings"; two units will pick different columns.
- Cursor pagination sort key and tiebreaker are unspecified (conventions only say "cursor").
Fix: one AD or convention line each.

**M4. Diagram misses dependencies the FRs require.**
Diagram edges omit: profiles -> catalog (favorites, FR-13), ratings -> catalog for song-belongs-to-album validation (FR-6), social/profiles/reviews/ratings -> accounts (user existence). AD-2 has ratings -> listenlist with the event type owned by ratings, so listenlist depends on ratings's package; "no cycles" is stated but the direction of the event dependency is not. Also FR-13 is mapped to AD-3, but AD-3 only covers feed reads; the map is inconsistent. FR-8 public Listenlist view, FR-9 follower/following lists have no stated read path.

**M5. Client-side divergence not covered.** No rule on SPA structure, state/data fetching, routing, or how FR-1's "prompt sign-up on guest write attempt" is implemented (401 handling convention). Testing strategy and local dev setup are absent. AD-7 covers only the contract.

## 2. Rule enforceability

- **AD-1 (H):** "enforced boundaries" is claimed but no mechanism is named (Spring Modulith `verify()`, ArchUnit, JPMS). "Sole writer" is likewise not enforced unless per-module DB roles exist. Only the feed role is described in AD-3, which implies multiple datasources, undeclared. Add the mechanism as a build-failing test.
- **AD-2 (M):** "listeners run inside the originating transaction" is a decision, but the failure behavior is unstated (listener failure rolls back the rating). Acceptable, but say so. Idempotence not needed in-process.
- **AD-3 (M):** the SELECT-only role has no config owner; Flyway per-module schema plus separate role creation is not assigned to a migration set. Feed reads `reviews`, but FR-10 only needs ratings; fine for Profile.
- **AD-5 (ok):** enforceable via CHECK and edge validation.
- **AD-8 (H):** several loose ends.
  - `SameSite` value not stated (Lax vs Strict) and no CSRF stance for the cookie-authenticated refresh endpoint.
  - "SPA and API share one registrable domain" is a constraint, but no domain/DNS/TLS issuance (Let's Encrypt? Certbot in nginx?) decision exists. Deployment cannot start without it.
  - Email verification is required, but the token design, expiry, resend, and what an unverified user may do are undecided; password recovery is deferred while email is mandatory, so lost passwords lock users out (acceptable but should be an explicit open question).
  - "BCrypt or Argon2" is an either/or; pick one.
  - Roles as a JWT claim with a 15-minute life means demoting Staff takes up to 15 minutes; note it.
- **AD-9 (M):** "maximum size per kind" and "resize before upload" are client-trusted; no value given. Lifecycle for replaced/orphaned objects is not decided. Album art upload by Staff (`uploads` for album art) has no owner rule for which module stores the key.
- **AD-10 (M):** rate-limit numbers are unspecified; "any new AWS service needs a cost bound" is a process rule, not testable. Acceptable if labelled as a guideline.
- **AD-11 (M):** "restore actually exercised" is not a checkable artifact; require a documented restore drill and a date. Backup schedule and retention values are absent.

## 3. Deferred: could it cause divergence?

- Password recovery: safe to defer, but interacts with mandatory email verification (see AD-8).
- Mobile token storage: safe (no mobile in MVP).
- Follow Artists, Review likes/replies: safe, but note Review is designed as one per (user, album) with no thread; okay.
- Catalog data sources: deferred, but AD-9 and catalog schema already assume manual entry with images; fine.
- "Observability beyond logs and an availability alarm": the availability alarm is not defined in any AD or in the topology; add or drop.
- Nothing in Deferred blocks unit parallelism, except that Terraform-deferred means infra is hand-built, so the deployment unit needs a written runbook (missing).

## 4. FR coverage

All 14 FRs appear in the map. Weak spots:
- FR-1: guest prompts are a client behavior with no convention.
- FR-3: no delete/removal semantics (H1).
- FR-8: cross-module removal covered; public read and re-add rules missing.
- FR-9/FR-14: follower lists and profile activity endpoints have no listed ownership beyond module names.
- FR-12: genres undefined (H2).
- FR-13: map cites AD-3 wrongly (should be AD-1).
- FR-2 log out: refresh-token revocation on logout is implied by AD-8 but not stated.
- NFR usability and privacy (all public) not mentioned; privacy is trivially satisfied but should be recorded so no unit adds visibility flags.

## 5. Dimensions

- Deployment: topology decided (AD-11); CI/CD pipeline, environments (staging vs prod), deploy procedure, rollback, DNS/TLS not decided, deferred, or asked. **Gap.**
- Infra: decided (EC2, S3, SES); no HA or single-host risk accepted explicitly; IAM covered. Mostly ok.
- Operations: backups decided, logs decided, alarm mentioned only in Deferred; no runbook, no secrets storage location beyond "environment variables" (where do they live on the host). **Partial.**
- Security: AD-8, AD-9, AD-10 cover it; CSRF, CORS, security headers, dependency scanning not addressed. **Partial.**
- Data: schema-per-module, Flyway, backups covered; catalog/genre/favorite model, cross-schema FKs, and deletion cascade are open. **Gap.**
- No "Open Questions" section exists, so undecided items have nowhere to live; several of the above should be logged there.

## 6. Minor

- Stack versions (Spring Boot 4.1.1, React 19.3, Vite 8, PostgreSQL 18) could not be verified here; confirm against current releases before build. Java 25 LTS is plausible.
- t4g.small with a JVM heap near 512 MB plus Postgres plus nginx in 2 GB is tight; the "move to t4g.medium" trigger is vague. Define the memory limits per container.
- Spring Boot's own tag `paradigm` line says "modular monolith" while the `feed` exception in AD-1/AD-3 is stated twice; consolidate.

## Top actions
1. Decide catalog/content deletion semantics and cross-schema FK policy (H1).
2. Add data-model decisions for genres, favorites, artist, follow, and staff bootstrap (H2).
3. Name an enforcement mechanism for AD-1 and the DB-role setup (H-AD-1).
4. Fix Feed/pagination ordering key and log/unlog lifecycle rules (H3).
5. Add an Open Questions section and fill deployment (CI/CD, DNS/TLS, secrets) and CSRF/SameSite.
