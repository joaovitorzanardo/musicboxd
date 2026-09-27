---
tracker_id: "MBD-41"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-41"
tracker_status: "backlog"
id: 4
type: story
title: "Write, edit, and delete a Review — owner-only, with a public read"
parent: epic-ciclo-avaliacao
after: [3.1]
risk: high
---

# Write, edit, and delete a Review — owner-only, with a public read

## Description

Adds reviews (one per user per album, AD-6) with create/edit/(hard-)delete endpoints, each verifying the caller's token matches the Review's author before allowing the write — the first application of this epic's AD-8 resource-ownership rule — plus a public read endpoint (list reviews by album; a user's own review) that the Album page and Profile need to display it.

## Acceptance Criteria

Verify: A User writes, edits, and deletes their own Review; a different User's token gets 403 on edit or delete of that same Review, called directly against the API, not only blocked by the UI; a Guest can read an Album's reviews and a User's reviews with no token.

## References

- parent — _bmad-output/initiative-musicboxd/epic-ciclo-avaliacao/epic-ciclo-avaliacao.md
- ARCHITECTURE-SPINE.md#ad-6
- ARCHITECTURE-SPINE.md#ad-8

## Notes

- High risk check: a person confirms User B cannot edit or delete User A's Review through a direct API call, not just through the SPA, before merge.
