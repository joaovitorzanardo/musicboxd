# Runbook: MBD-13 rate limits (tunables and live verification)

Goal (acceptance criterion): **a burst of requests from one IP is throttled by nginx, an oversized request body is
rejected, and a demo endpoint using the Spring limiter rejects a caller past its configured per-user limit.**

This runs against the live host after the MBD-13 merge has been deployed by CD (MBD-10), so you run it. Every
command is on a single line so it can be pasted as is. Commands marked **[local]** run on your machine (Git Bash is
fine). Commands marked **[host]** run in an SSM session:

```bash
aws ssm start-session --target <INSTANCE_ID> --region us-east-1
```

Values: domain `musicboxd.com.br`, stack at `/opt/musicboxd`, env file `/etc/musicboxd/stack.env`.

## Tunables (where each limit lives)

| Limit | Value | Where |
| --- | --- | --- |
| nginx per-IP rate | `rate=10r/s` (zone `perip`, 10 MB of state) | `limit_req_zone` in `deploy/nginx/nginx.conf` and `deploy/nginx/nginx.prod.conf.template` |
| nginx per-IP burst | `burst=30 nodelay`, rejected with `429` | `limit_req` in the same two files (prod: 443 server only; port 80 only redirects and serves ACME) |
| Request body cap | `client_max_body_size 1m`, rejected with `413` | same two files |
| Spring per-user policies | `demo`: 5 requests per `1m`; auth policies (login, register, verification email): see `runbook-mbd-23-auth-rate-limits.md` | `musicboxd.rate-limit.policies.*` in `api/src/main/resources/application.yml` |
| Spring tracked keys | 10 000 buckets per policy (Bucket4j buckets in a Caffeine cache); idle ones expire after a refill period, and when full Caffeine evicts rather than rejecting | `MAX_TRACKED_KEYS` in `RateLimitConfig` |

Keep the two nginx files in sync; `deploy/tests/test-nginx-conf.sh` checks the prod template has the directives.
A Spring policy can be overridden on the host without a rebuild: add a line such as
`MUSICBOXD_RATELIMIT_POLICIES_DEMO_CAPACITY=10` (relaxed binding) to `/etc/musicboxd/api.env` (the `api` service reads
`postgres.env`, shared with `postgres`, and `api.env`; api-only settings go in `api.env`), then recreate the api container. **[host]**
`cd /opt/musicboxd && sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d api`

Applying a limit to a new endpoint (later epics): add `@RateLimited(policy = "<name>")` to the controller method and
a `musicboxd.rate-limit.policies.<name>` entry. The default caller key is the authenticated user, else the client IP.
Spring limiter state is in memory: a restart or deploy gives every caller a full bucket again.

## 0. Prerequisites

- [ ] The deployed tag contains MBD-13. **[host]** `sudo grep '^IMAGE_TAG=' /etc/musicboxd/stack.env` matches the merge
  commit's `sha-...`.
- [ ] The stack is healthy. **[local]** `curl -sS -o /dev/null -w "%{http_code}\n" https://musicboxd.com.br/api/v1/health`
  (expect `200`).
- [ ] Locally, before merging: `bash deploy/tests/test-nginx-conf.sh && bash deploy/tests/test-nginx-ratelimit.sh`
  and `cd api && ./gradlew test` are green.

Run each check below from one machine, and wait about 5 seconds of idle between checks so the nginx bucket refills.

## 1. nginx throttles a burst from one IP

**[local]**

```bash
for i in $(seq 1 150); do curl -s -o /dev/null -w '%{http_code}\n' https://musicboxd.com.br/; done | sort | uniq -c
```

Expect a mix of `200` and `429` (no `503`). If every line is `200`, your requests are slower than 10 r/s; send them
in parallel instead:

```bash
seq 1 150 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code}\n' https://musicboxd.com.br/ | sort | uniq -c
```

Then confirm a normal page load is not throttled: open `https://musicboxd.com.br/` in a browser with the developer
tools Network tab open and reload a few times. Expect no `429` rows.

## 2. An oversized request body is rejected

**[local]**

```bash
head -c 2097152 /dev/zero | curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/octet-stream' --data-binary @- https://musicboxd.com.br/api/v1/anything
```

Expect `413`. (nginx's built-in default is also 1 MB, so this check confirms the cap is in force, not that the
explicit directive is; `deploy/tests/test-nginx-conf.sh` checks the directive.)

## 3. The Spring limiter rejects a caller past its per-user limit

**[local]** Seven calls as `alice` (policy `demo` allows 5 per minute):

```bash
for i in $(seq 1 7); do curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Demo-User: alice' https://musicboxd.com.br/api/v1/demo/rate-limited; done
```

Expect five `200` then two `429`. Then:

```bash
curl -si -H 'X-Demo-User: alice' https://musicboxd.com.br/api/v1/demo/rate-limited | grep -iE '^HTTP|^retry-after'
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Demo-User: bob' https://musicboxd.com.br/api/v1/demo/rate-limited
```

Expect `429` with a `Retry-After` header (seconds) for alice, and `200` for bob (a different user is not affected).

## Results

| Check | Expected | Observed | Date |
| --- | --- | --- | --- |
| 1. Burst from one IP | mix of `200` and `429`, no `503` | | |
| 1. Browser page load | no `429` | | |
| 2. 2 MiB POST | `413` | | |
| 3. alice x7 | 5 x `200`, then `429` | | |
| 3. alice `Retry-After` | header present | | |
| 3. bob | `200` | | |
