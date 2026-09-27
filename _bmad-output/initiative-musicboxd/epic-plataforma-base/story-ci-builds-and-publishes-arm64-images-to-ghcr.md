---
tracker_id: "MBD-7"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-7"
tracker_status: "backlog"
id: 3
type: story
title: "CI builds and publishes arm64 images to GHCR"
parent: epic-plataforma-base
after: [1]
hitl: true
risk: medium
---

# CI builds and publishes arm64 images to GHCR

## Description

GitHub Actions builds the api and web images for arm64 on every push to main and pushes them to GitHub Container Registry.

## Acceptance Criteria

Verify: A push to main produces a new arm64 image tag in GHCR for both api and web, visible in the registry.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md

## Notes

- Registry: GHCR (user's decision, 2026-09-27) — simpler token-based auth, no extra AWS IAM.
