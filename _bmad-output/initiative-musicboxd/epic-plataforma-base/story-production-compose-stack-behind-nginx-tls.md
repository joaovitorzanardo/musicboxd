---
tracker_id: "MBD-9"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-9"
tracker_status: "backlog"
id: 5
type: story
title: "Production Compose stack behind nginx TLS"
parent: epic-plataforma-base
after: [4, 1]
hitl: true
risk: medium
---

# Production Compose stack behind nginx TLS

## Description

Deploys the api/postgres/nginx Compose stack to the EC2 host, with nginx terminating TLS via certbot against a placeholder domain, auto-renewing and reloading nginx on renewal.

## Acceptance Criteria

Verify: The placeholder domain serves the SPA over HTTPS from the EC2 host with a valid certificate that renews automatically and reloads nginx.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md
- ARCHITECTURE-SPINE.md#ad-11
- ARCHITECTURE-SPINE.md#stack

## Notes

- Open question: Production domain is not yet decided (user's call, 2026-09-27); this ticket uses a placeholder domain, and the cert plus SPA/CORS origin get swapped once a real one is chosen — that swap is not this ticket's work.
