# Musicboxd Roadmap (outside MVP)

## SHOULD — next
- **Lists:** create Album lists, public or private; others can follow public lists.
- **Review interaction:** like and reply to Reviews (flat vs nested undecided).
- **Follow Artists:** new releases from followed Artists top the Feed. Target Feed order: new releases, friends' recent Ratings, recommendations. Needs release data, likely automated imports (unresolved dependency).
- **Request this album:** button when search finds nothing; requests queue for Staff.

## COULD — later
- **Recommendations:** black-box Feed section from followed Artists, logged Songs/Albums, and favorite genres; no "because" labels. Favorite genres solve cold start.
- **Streaming integration:** monthly stats (top Song, Artist, genre) shown to friends and on the Profile; no deeper analysis.
- **Automated Catalog imports:** Albums and Songs via APIs and scrapers; source (e.g. MusicBrainz, Spotify, Discogs) subject to licensing and cover art.

## Insights
- Song Ratings fuel favorite Songs, stats, and recommendations.
- Request-this-album pairs with manual Catalog entry as the early catalog-gap loop.

## Superseded
Brainstorm rule where the Album Score was computed from Song Ratings with an either/or logging mode was replaced: Album and Songs are rated independently.
