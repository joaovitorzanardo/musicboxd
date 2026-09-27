---
tracker_id: "MBD-5"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-5"
tracker_status: "backlog"
id: 1
type: story
title: "Tracer bullet: API, SPA, and local Compose talk to each other"
parent: epic-plataforma-base
hitl: true
risk: low
---

# Tracer bullet: API, SPA, and local Compose talk to each other

## Description

Scaffolds api/, web/, deploy/ per the architecture layout, with a minimal Spring Boot health endpoint, a minimal React SPA screen, and a docker-compose.yml running api+postgres+nginx locally.

## Acceptance Criteria

Verify: Running `docker compose up` locally serves the SPA, which calls the API's health endpoint through nginx and shows the result.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md
- ARCHITECTURE-SPINE.md#structural-seed
