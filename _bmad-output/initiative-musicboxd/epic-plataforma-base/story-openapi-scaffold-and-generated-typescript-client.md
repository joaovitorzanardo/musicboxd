---
tracker_id: "MBD-6"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-6"
tracker_status: "backlog"
id: 2
type: story
title: "OpenAPI scaffold and generated TypeScript client"
parent: epic-plataforma-base
after: [1]
risk: low
---

# OpenAPI scaffold and generated TypeScript client

## Description

Adds springdoc-openapi to the API so it serves an OpenAPI document, and wires the SPA's build to generate its TypeScript client from that document.

## Acceptance Criteria

Verify: The API serves a valid OpenAPI JSON at its docs path, and the SPA build regenerates a matching TypeScript client with no manual edits.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md
- ARCHITECTURE-SPINE.md#ad-7
