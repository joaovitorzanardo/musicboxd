---
tracker_id: "MBD-38"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-38"
tracker_status: "backlog"
id: 1
type: story
title: "Tracer bullet: score an Album, see it Logged"
parent: epic-ciclo-avaliacao
after: [3.1]
risk: medium
---

# Tracer bullet: score an Album, see it Logged

## Description

Adds the ratings schema (album_rating: user+album unique, integer 0..10 with a CHECK, per AD-5), an endpoint to create/update a User's own Album Score (API takes 0..5 in 0.5 steps, converts at the edge), and a query returning whether the caller has Logged a given Album.

## Acceptance Criteria

Verify: A User gives an Album a Score of 4.5 and it reads back as 4.5 and Logged; 5.3 and -1 are both rejected; the row is unique per (user, album).

## References

- parent — _bmad-output/initiative-musicboxd/epic-ciclo-avaliacao/epic-ciclo-avaliacao.md
- ARCHITECTURE-SPINE.md#ad-4
- ARCHITECTURE-SPINE.md#ad-5
