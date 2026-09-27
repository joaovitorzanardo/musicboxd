---
tracker_id: "MBD-43"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-43"
tracker_status: "backlog"
id: 6
type: story
title: "Scoring an Album reacts on the Listenlist (AD-2 domain event)"
parent: epic-ciclo-avaliacao
after: [1, 5]
risk: high
---

# Scoring an Album reacts on the Listenlist (AD-2 domain event)

## Description

Publishes an in-process 'album scored' event from entry 1's write path; listenlist consumes it in the same transaction to remove that Album from the scoring User's Listenlist, and rejects adding an already-Logged Album.

## Acceptance Criteria

Verify: Scoring a Listenlisted Album removes it from the Listenlist in the same commit; adding an already-Logged Album to the Listenlist is rejected; retrying the event publish does not double-remove or corrupt state; forcing the listener to throw rolls back the Album Score write too (fails closed, AD-2) — the User is not left Logged with a stale Listenlist entry.

## References

- parent — _bmad-output/initiative-musicboxd/epic-ciclo-avaliacao/epic-ciclo-avaliacao.md
- ARCHITECTURE-SPINE.md#ad-2

## Notes

- High risk check: a person retries the event publish and confirms idempotency (no double effect, no lost removal); a person also forces a listener exception and confirms the whole transaction, including the rating write, rolls back — not just the Listenlist side.
