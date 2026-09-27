---
tracker_id: "MBD-47"
remote: "https://joaovitorzanardoorg-1788391142221.atlassian.net/browse/MBD-47"
tracker_status: "backlog"
id: 1
type: story
title: "Tracer bullet: upload avatar/cover, set bio"
parent: epic-perfil-favoritos
after: [3.4]
risk: medium
---

# Tracer bullet: upload avatar/cover, set bio

## Description

Extends the `profiles` schema stub (from epic-contas-acesso entry 1) with avatar_key, cover_key, and bio columns, and adds endpoints to set them, reusing the uploads presign contract for the images.

## Acceptance Criteria

Verify: A User uploads an avatar and a cover image via the presign flow, sets a bio, and all three read back on their own profile; the endpoints take no user id at all — they resolve 'whose profile' only from the caller's token — so User B's token against the same endpoints can only ever read or mutate B's own row.

## References

- parent — _bmad-output/initiative-musicboxd/epic-perfil-favoritos/epic-perfil-favoritos.md
- ARCHITECTURE-SPINE.md#ad-9
- ARCHITECTURE-SPINE.md#ad-13

## Notes

- Ownership note: profiles are 1:1 with the caller's token, so entries in this epic never take a foreign user id — this is the epic's application of the AD-8 rule it owns.
