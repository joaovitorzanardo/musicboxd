---
tracker_id: "MBD-8"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-8"
tracker_status: "backlog"
id: 4
type: story
title: "EC2 host, IAM role, and network provisioned"
parent: epic-plataforma-base
hitl: true
risk: medium
---

# EC2 host, IAM role, and network provisioned

## Description

Provisions the t4g.small EC2 instance, an IAM role scoped to the image bucket, backup bucket, and presigning, and a security group that opens only 80/443 publicly.

## Acceptance Criteria

Verify: The instance is reachable only on 80/443 from the internet; SSM session or IP-restricted SSH is the only shell access; the IAM role grants nothing beyond the image bucket, backup bucket, and presigning.

## References

- parent — _bmad-output/initiative-musicboxd/epic-plataforma-base/epic-plataforma-base.md
- ARCHITECTURE-SPINE.md#ad-11

## Notes

- Open question: This ticket scopes the IAM role to the image bucket by name/ARN before epic-catalogo-busca actually creates that bucket and its policy (AD-9). Reserve the bucket name now and hand it to that epic, or grant the role access to the bucket's future ARN pattern; either way, coordinate with epic-catalogo-busca's first S3 story.
