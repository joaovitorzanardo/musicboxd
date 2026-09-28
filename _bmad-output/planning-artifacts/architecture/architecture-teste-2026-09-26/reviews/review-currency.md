# Currency review: ARCHITECTURE-SPINE.md (as of 2026-09-26)

Method: web search and fetch only. The spine was not edited.
Confidence tags: CONFIRMED (a source states it), PARTIAL (inferred or not stated directly), UNCONFIRMED.

## Verdict
The stack table is current. No committed decision is out of date. There are four items to tighten: one small factual gap in AD-9, one likely-to-bite operational note in AD-11 (Let's Encrypt), and two version-precision notes. The "pg_trgm on Spring Boot 4" question was not researched and is not Boot-specific (see F5).

## Stack table

| Claim | Result | Evidence |
| --- | --- | --- |
| Spring Boot 4.1.1 | CONFIRMED current. 4.1.1 shipped 2026-08-20 (4.1.0 on 2026-06-10). OSS support runs to 2027-07-31. 4.0.x OSS support ends 2026-12-31, so 4.1 is the right line. | spring.io blog 4.1.1; endoflife.date/spring-boot |
| Spring Framework 7 | CONFIRMED. 4.1.1 ships Spring Framework 7.0.9, Security 7.1.1, Hibernate 7.4.5. | spring.io 4.1.1 announcement |
| Java 25 (LTS) | CONFIRMED as an LTS. Boot 4.1 supports Java 17 to 26; Java 25 is a tested baseline. | endoflife.date; Boot 4.1 release notes |
| Java 25 on arm64 / Graviton | CONFIRMED. `eclipse-temurin:25-jre` (and the `-jammy` variant) is published for arm64/v8. | Docker Hub eclipse-temurin |
| PostgreSQL 18 "current minor at build" | CONFIRMED. Current minor is 18.6 (released 2026-08-13; 18.5 was skipped after a regression). PG19 is in beta 3, so 18 is right for production. | postgresql.org news 18.6 |
| React 19.3 | CONFIRMED. 19.3.0 was released 2026-09-09. It is two weeks old, so pin the exact version in the lockfile. | react.dev/versions |
| Vite 8 | CONFIRMED. Current is 8.3.1 (8.x line). "8" is fine. | vite.dev/releases |
| nginx, Compose v2, t4g.small, SES | Not researched (generic or pin-at-build). No issue found. |  |

## Library choices under Spring Boot 4

- **Flyway: valid, with a Boot 4 gotcha.** Boot 4 split auto-configuration into modules. You must depend on `spring-boot-starter-flyway`. Bare `flyway-core` no longer auto-runs migrations, and this fails silently (reported on Boot 4 + Java 25). You also need `flyway-database-postgresql`. CONFIRMED (Boot 4.0 migration guide, Spring blog "Modularizing Spring Boot"). The spine says "Flyway" only; add the starter to the build notes.
- **springdoc: valid.** springdoc 3.x is the Boot 4 line. 3.0.x targeted Boot 4.0.x. 3.1.0 states "Upgrade Spring Boot to 4.1.0", and 3.1.1 is the latest. Use springdoc 3.1.x, not 2.x, which is Boot 3 only. Release dates from the fetch were garbled, so this is PARTIAL on exact dates and CONFIRMED on the line.
- **Flyway "one migration set per module schema"** is a design choice, not a currency issue. Boot's auto-config runs one Flyway instance by default. Multiple schemas need either several `Flyway` beans or one location tree. Flag this to the build story.
- **pg_trgm: valid.** It is a PostgreSQL contrib extension, independent of Spring. Not reverified against PG18 docs in this pass. UNCONFIRMED only in the sense of no direct check. It needs `CREATE EXTENSION`, which the Docker `postgres` image allows. Note that Hibernate does not support `ILIKE` natively through JPQL; use native queries or `lower() like`.

## AD-9: S3 presigned PUT + public read on a prefix
Mostly valid, with one missing detail.
- New buckets have ACLs disabled (Object Ownership = bucket owner enforced) and all four Block Public Access settings on by default. CONFIRMED (AWS docs). Presigned PUTs work with all four BPA settings on, because they are signed requests.
- Public read on a prefix requires a bucket policy granting `s3:GetObject` on `arn:aws:s3:::bucket/images/*`. That policy is only accepted if **BlockPublicPolicy** and **RestrictPublicBuckets** are turned off (for that bucket, and at the account level if set there). The spine's wording "public read on the image prefix only, all other public access blocked" is achievable only via the policy scope, not via BPA. Turning those two off does not block the other prefixes by itself; the policy's Resource scope does that. Keep BlockPublicAcls and IgnorePublicAcls on. State this in AD-9.
- Use bucket-owner-enforced with no ACLs. Do not put an `x-amz-acl` in the presign.
- Browser PUT needs a CORS rule on the bucket (allowed origin = SPA domain, method PUT, header Content-Type). Not mentioned in the spine. Also enforce the type and size at issue time. A presigned PUT cannot enforce max size; use a presigned POST policy (`content-length-range`) if the cap must be server-enforced. This is a real gap against "maximum size per kind".
- Keep the backup bucket fully private, in a separate bucket, so the public-policy exception does not touch it (spine already uses separate buckets).

## AD-11 / TLS: Let's Encrypt + certbot + nginx in Docker
Valid pattern (certbot webroot or a certbot container sharing volumes with nginx; widely documented). Currency note: Let's Encrypt is shortening lifetimes. The default profile moves to 64-day certificates on 2027-02-10 and 45-day on 2028-02-16 (source: letsencrypt.org 2025-12-02 announcement). The spine does not mention renewal. Add: automated renewal (cron or a renew loop) plus an nginx reload, and a renewal-failure alarm. This matters more at 45 days. Prefer a renewal design that does not depend on 90-day slack.

## Findings, ranked
1. F1 (medium): AD-9 does not say the bucket policy requires BlockPublicPolicy/RestrictPublicBuckets off, or that a CORS rule is needed. Presigned PUT cannot enforce a size cap; use presigned POST if the cap is a hard rule.
2. F2 (medium): Boot 4 requires `spring-boot-starter-flyway`; the silent-no-migration failure is documented. Also pick springdoc 3.1.x.
3. F3 (low-medium): Let's Encrypt lifetimes shrink to 64 days (2027-02) and 45 days (2028-02). Renewal automation and alerting are not in the spine.
4. F4 (low): React 19.3 is 17 days old and Vite is at 8.3; pin exact versions. PostgreSQL 18.6 is the current minor.
5. F5 (low): One Flyway config per module schema needs multiple Flyway beans in Boot's auto-config; not a currency issue.

Nothing else in the Stack table was found stale.

Sources: spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now; endoflife.date/spring-boot; postgresql.org 18.6 release news; react.dev/versions; vite.dev/releases; github.com/springdoc/springdoc-openapi/releases; Spring Boot 4.0 Migration Guide and spring.io "Modularizing Spring Boot"; Docker Hub eclipse-temurin; AWS S3 Block Public Access docs; letsencrypt.org/2025/12/02/from-90-to-45.
