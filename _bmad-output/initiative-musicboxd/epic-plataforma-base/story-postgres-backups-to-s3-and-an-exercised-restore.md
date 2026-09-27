---
tracker_id: "MBD-11"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-11"
tracker_status: "backlog"
id: 7
type: story
title: "Postgres backups to S3 and an exercised restore"
parent: epic-plataforma-base
after: [5]
hitl: true
risk: high
---

# Postgres backups to S3 and an exercised restore

## Description

Adds a scheduled pg_dump from the production Postgres container to S3 with bounded retention, and exercises one full restore from a backup.

## Acceptance Criteria

Verify: A backup file appears in S3 on schedule, and a restore from it into a fresh Postgres actually completes with the same data.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md
- ARCHITECTURE-SPINE.md#ad-11

## Notes

- High risk check: a person confirms the restored data matches the source before this ticket closes.
