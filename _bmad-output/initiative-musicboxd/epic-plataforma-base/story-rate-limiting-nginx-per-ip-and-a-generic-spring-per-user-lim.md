---
tracker_id: "MBD-13"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-13"
tracker_status: "backlog"
id: 9
type: story
title: "Rate limiting: nginx per-IP and a generic Spring per-user limiter"
parent: epic-plataforma-base
after: [2, 5]
risk: medium
---

# Rate limiting: nginx per-IP and a generic Spring per-user limiter

## Description

Adds an nginx limit_req zone for all routes plus request body and upload size caps, and a reusable Spring per-user rate-limit mechanism that later epics apply to login, registration, email sending, and upload-URL issuance.

## Acceptance Criteria

Verify: A burst of requests from one IP is throttled by nginx, an oversized request body is rejected, and a demo endpoint using the Spring limiter rejects a caller past its configured per-user limit.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md
- ARCHITECTURE-SPINE.md#ad-10

## Notes

- This ticket builds the generic per-user limiter mechanism only; epic-contas-acesso and epic-catalogo-busca apply it to their specific endpoints when those exist.
- Depends on entry 2 (OpenAPI scaffold) because the demo endpoint proving the per-user limiter rides the established OpenAPI/generated-client pipeline (AD-7), not a one-off route.
- AD-10's request-body and upload-size caps are covered here as generic guardrails; upload-specific limits (avatar/cover/album-art byte sizes) are epic-catalogo-busca's and epic-perfil-favoritos's own concern.
