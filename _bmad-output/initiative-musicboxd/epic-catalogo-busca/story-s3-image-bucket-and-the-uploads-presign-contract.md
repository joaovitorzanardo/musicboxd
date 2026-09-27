---
tracker_id: "MBD-31"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-31"
tracker_status: "backlog"
id: 4
type: story
title: "S3 image bucket and the uploads presign contract"
parent: epic-catalogo-busca
after: [1.4]
hitl: true
risk: high
---

# S3 image bucket and the uploads presign contract

## Description

Creates the S3 image bucket (CORS for the app origin, ACLs disabled, public read on the image prefix only, all other access blocked) and the uploads module: presigned POST issuance, a confirm endpoint, and content-type/size validation, first used for album art.

## Acceptance Criteria

Verify: A client requests a presigned POST, uploads a jpeg/png/webp under the size limit directly to S3, confirms it, and the object is readable over HTTPS at the bucket domain; an oversized or wrong-type upload is rejected before it reaches S3.

## References

- parent — _bmad-output/initiative-musicboxd/epic-catalogo-busca/epic-catalogo-busca.md
- ARCHITECTURE-SPINE.md#ad-9

## Notes

- High risk check: a person confirms the bucket policy grants public read ONLY on the image prefix, nothing else, before merge — a misconfigured public bucket is a real data exposure.
- Decision: image bucket name is `musicboxd-images` (single bucket; key prefixes distinguish avatar/, cover/, and album-art/), matching epic-plataforma-base entry 4's IAM role, already scoped to that exact name (set-check finding, 2026-09-27).
