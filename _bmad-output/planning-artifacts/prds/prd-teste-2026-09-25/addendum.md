# Musicboxd — Addendum

Depth preserved from `brainstorm-intent.md` that does not belong in the PRD narrative.

## SHOULD — build later
- **Lists:** create lists of Albums; public or private; other Users can follow public lists.
- **Review interaction:** like a Review; reply to a Review (flat vs nested threads undecided).
- **Following Artists:** follow Artists as well as friends. New releases from followed Artists go at the top of the Feed. Target Feed order: 1) new releases from followed Artists, 2) friends' recent Ratings, 3) recommendations. Depends on release data (likely automated imports).
- **Catalog gaps:** "Request this album" button when search finds nothing; requests go to a queue for Staff to fill.

## COULD — future
- **Recommendations:** black-box section in the Feed, based on followed Artists, logged Songs/Albums, and favorite genres. No "because you rated X" explanations. Favorite genres on the Profile solve cold start.
- **Streaming integration:** connect streaming services; monthly stats (most streamed Song, Artist, genre) shown to friends and on the public Profile; no deeper analysis.
- **Automated catalog imports:** Albums and Songs via APIs and web scrapers; source choice (e.g. MusicBrainz, Spotify, Discogs — licensing and cover art) is for the technical session.

## Insights from brainstorming
- Song-level Ratings are the shared fuel for favorite Songs, stats, and recommendations.
- Request-this-album pairs with manual Catalog entry as the early catalog-gap loop.

## Decided against (for now)
Artist accounts; "because" labels; direct-vs-computed Album Score side by side; ratings-vs-plays analysis; listening context tags.

## Superseded brainstorm rule
The brainstorm had the Album Score computed from Song Ratings once all Songs were Rated, with an either/or logging mode. Replaced during PRD (2026-09-25): the User rates the Album and Songs independently; the Album shows only the given score.
