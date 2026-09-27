---
tracker_id: "MBD-39"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-39"
tracker_status: "backlog"
id: 2
type: story
title: "Delete an Album Score un-Logs the Album"
parent: epic-ciclo-avaliacao
after: [1]
risk: medium
---

# Delete an Album Score un-Logs the Album

## Description

Adds an endpoint for a User to delete their own Album Score, a real DELETE (AD-6's hard-delete convention), which un-Logs the Album for them.

## Acceptance Criteria

Verify: After deleting the Score, the Album is no longer Logged for that User; the User can then set a new Score on the same Album.

## References

- parent — _bmad-output/initiative-musicboxd/epic-ciclo-avaliacao/epic-ciclo-avaliacao.md
