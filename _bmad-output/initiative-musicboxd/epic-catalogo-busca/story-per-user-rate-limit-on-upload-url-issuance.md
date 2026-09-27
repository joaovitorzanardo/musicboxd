---
tracker_id: "MBD-33"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-33"
tracker_status: "backlog"
id: 6
type: story
title: "Per-user rate limit on upload-URL issuance"
parent: epic-catalogo-busca
after: [4, 1.9]
risk: medium
---

# Per-user rate limit on upload-URL issuance

## Description

Applies the platform's generic per-user rate limiter (epic-plataforma-base) to the presign-issuance endpoint.

## Acceptance Criteria

Verify: Repeated presign requests from one account are throttled past the configured per-user limit.

## References

- parent — _bmad-output/initiative-musicboxd/epic-catalogo-busca/epic-catalogo-busca.md
- ARCHITECTURE-SPINE.md#ad-10
