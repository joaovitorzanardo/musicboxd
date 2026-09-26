# Musicboxd — Product Intent

Source: brainstorming session 2026-09-25 (`.memlog.md` in this folder).

## Main idea

**Musicboxd is a music tracker and social media for music.** It is a Letterboxd-style site where people log the albums and songs they listen to, rate and review them, and follow friends to see what they are listening to.

- Built for fun, so competing products don't matter.
- **Audience:** music geeks who want a place to express their taste and passion, share it, and check in on friends.
- **Secondary audience:** artists, who can see how people rated their work. There are no special artist accounts, and artists see ratings like any other user.
- Scope for this phase: main product features only. Technical decisions (data sources, APIs, stack) are for a separate session.

---

## MUST — build right away

**Logging and rating**
- Log an album you listened to.
- Score from 0 to 5, with half stars.
- Write a review on an album.
- Rate individual songs. The user chooses to rate just the album, or rate each song.
- The album score is computed from the songs only once every song is rated.

**Listenlist**
- Add an album to a listenlist (the watchlist equivalent).

**Social basics**
- Follow friends and be followed.
- Feed showing what friends recently rated (albums and songs).

**Public profile**
- Profile picture and cover picture.
- Bio.
- Favorite genres (later used for recommendations).
- 5 favorite albums of all time.
- 5 favorite songs of all time.

**Catalog**
- Staff add albums and songs manually.

---

## SHOULD — build later

**Lists**
- Create lists with different albums.
- Lists can be public or private.
- Users can follow other users' public lists.

**Review interaction**
- Like a review.
- Reply to a review. Open question: flat replies or nested threads.

**Following artists**
- Follow artists as well as friends.
- New releases from followed artists appear at the top of the feed.
- Feed order: 1) new releases from followed artists, 2) friends' recent ratings, 3) recommendations.
- Dependency: release data likely needs the automated imports listed under Could.

**Catalog gaps**
- "Request this album" button when a search finds nothing. It goes into a queue for staff to fill.

---

## COULD — future

**Recommendations**
- A black-box recommendation section in the feed.
- Based on followed artists, logged songs and albums, and favorite genres.
- Kept simple on purpose, with no "because you rated X" explanations.

**Streaming integration**
- Connect streaming services.
- Monthly stats: most streamed song, artist and genre.
- Stats are shown to friends and on the public profile, with no deeper analysis.

**Automated catalog imports**
- Bring in albums and songs through APIs and web scrapers.
- The choice of sources is for the technical session.

---

## Decided against (for now)

- Special artist accounts.
- "Because you rated X" labels on recommendations.
- Showing the direct album score next to the computed one.
- Ratings-vs-plays analysis.
- Listening context tags such as mood, place, or first listen vs re-listen (not taken up).

## Open questions for later

- Which data sources and APIs feed the catalog (licensing, cover art)?
- Flat or nested replies on reviews?
- How do "follow artists" and release data work if automated imports come last?

## Next step

This file can be used as input to `bmad-product-brief` or `bmad-prd`.
