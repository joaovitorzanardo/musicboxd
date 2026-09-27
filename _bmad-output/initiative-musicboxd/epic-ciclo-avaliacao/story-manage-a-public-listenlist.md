---
tracker_id: "MBD-42"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-42"
tracker_status: "backlog"
id: 5
type: story
title: "Manage a public Listenlist"
parent: epic-ciclo-avaliacao
after: [3.1]
risk: medium
---

# Manage a public Listenlist

## Description

Adds listenlist_item (owner-only add/remove, AD-8 ownership check applied) for a User's public list of Albums they want to listen to.

## Acceptance Criteria

Verify: A User adds and removes an Album from their own Listenlist; a different User's token gets 403 removing an item that isn't theirs; the list is visible to a Guest.

## References

- parent — _bmad-output/initiative-musicboxd/epic-ciclo-avaliacao/epic-ciclo-avaliacao.md
- ARCHITECTURE-SPINE.md#ad-8
