---
tracker_id: "MBD-10"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-10"
tracker_status: "backlog"
id: 6
type: story
title: "CD deploys CI images to the EC2 host"
parent: epic-plataforma-base
after: [3, 5]
hitl: true
risk: medium
---

# CD deploys CI images to the EC2 host

## Description

Extends the GitHub Actions workflow to pull the newly published GHCR images onto the EC2 host and restart the Compose stack after each successful CI build.

## Acceptance Criteria

Verify: A push to main results in the EC2 host running the new image tags within the workflow's run, with no manual step.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md
