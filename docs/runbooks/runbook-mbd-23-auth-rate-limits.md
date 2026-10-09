# Runbook: MBD-23 auth rate limits (tunables and live verification)

Goal (acceptance criterion): **repeated login attempts, repeated registrations, and repeated verification-email
requests from one account are each throttled past their configured limit.**

Run this against the live host after CD (MBD-10) has deployed the MBD-23 merge. **[local]** commands run on your
machine (Git Bash); **[host]** commands run in an SSM session (`aws ssm start-session --target <INSTANCE_ID> --region us-east-1`).
Values: domain `musicboxd.com.br`, stack at `/opt/musicboxd`, env files in `/etc/musicboxd/`.

## Tunables

Each endpoint has two buckets: one per email in the body (whichever IP sends it) and one per client IP.
The email key is the normalized address (`strip` + lowercase), hashed. It is the same for known and unknown accounts.

| Policy | Capacity / refill | Endpoint |
| --- | --- | --- |
| `login-per-ip` | 20 / 15m | `POST /api/v1/auth/login` |
| `login-per-email` | 10 / 15m | same |
| `register-per-ip` | 10 / 1h | `POST /api/v1/auth/register` |
| `register-per-email` | 8 / 1h (every attempt counts, including a 409 for a taken username) | same |
| `verification-email-per-ip` | 3 / 15m | `POST /api/v1/auth/verification-email` |
| `verification-email-per-email` | 5 / 24h | same |

Refill is continuous: with 10 per 15m, one attempt comes back every 90 s. Override on the host without a rebuild by
adding one line to `/etc/musicboxd/api.env`, then recreating the api. These policy names contain dashes, and a plain
environment variable cannot name a map key with a dash (`..._LOGINPEREMAIL_...` would create a new, unused policy
`loginperemail`), so use `SPRING_APPLICATION_JSON`, unquoted, with every override in that one line:
`SPRING_APPLICATION_JSON={"musicboxd":{"rate-limit":{"policies":{"login-per-email":{"capacity":20,"refill-period":"15m"}}}}}`
Recreate the api: **[host]** `cd /opt/musicboxd && sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d api`
Then check the new container has it: **[host]** `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec api env | grep SPRING_APPLICATION_JSON`.
That only proves the variable is set; to prove the limit changed, rerun the matching Verification step below with the new count.
State is in memory: a restart or deploy refills every bucket.

Known trade-off: anyone who knows an address can keep its login throttled, and its verification-email resend too.
Holding a login locked costs them one request per `refill-period / capacity` (every 90 s at 10 per 15m); after
they stop, the owner waits at most that long. Raising the capacity does **not** fix this, it only makes the
attacker send more often. Holding resend locked denies the owner a new link, but each of the attacker's resends
already mails a fresh link to the owner's inbox. If either is abused, the fix is code, not tuning: exempt a
caller that already holds a session for the account, or add a bucket keyed on email + IP.

## Verification

Use a throwaway address you control (`<EMAIL>`). The per-email limits are what this checks, so it is fine to
send everything from your own IP, but each section below spends your IP's tokens too: wait 15 minutes between
sections, or run them from different networks.

- [ ] **Login.** **[local]** Send 11 wrong-password logins for `<EMAIL>`:
  `for i in $(seq 11); do curl -sS -o /dev/null -w "%{http_code} " -H 'Content-Type: application/json' -d '{"email":"<EMAIL>","password":"wrong-password"}' https://musicboxd.com.br/api/v1/auth/login; done; echo`
  Expected: ten `401` then `429`. Then the right password also gets `429` with a `Retry-After` header:
  `curl -sS -i -H 'Content-Type: application/json' -d '{"email":"<EMAIL>","password":"<PASSWORD>"}' https://musicboxd.com.br/api/v1/auth/login | grep -iE '^HTTP|^retry-after'`
- [ ] **Registration.** **[local]** Send 9 sign-ups for a new `<EMAIL2>`, each with a fresh username:
  `for i in $(seq 9); do curl -sS -o /dev/null -w "%{http_code} " -H 'Content-Type: application/json' -d "{\"email\":\"<EMAIL2>\",\"password\":\"correct-horse\",\"username\":\"rl_test_$i\"}" https://musicboxd.com.br/api/v1/auth/register; done; echo`
  Expected: `201`, seven `409`, then `429` (9 stays under `register-per-ip`'s 10, so the 429 is the per-email
  limit). Exactly one verification email arrives at `<EMAIL2>`.
- [ ] **Verification email.** **[local]** For the unverified `<EMAIL2>`, send 6 resends, one every 5 minutes so the per-IP
  limit (3 / 15m) never trips:
  `curl -sS -o /dev/null -w "%{http_code}\n" -H 'Content-Type: application/json' -d '{"email":"<EMAIL2>"}' https://musicboxd.com.br/api/v1/auth/verification-email`
  Expected: five `202` then `429`. The inbox has at most 6 emails for `<EMAIL2>` in 24 h (1 sign-up + 5 resends).
- [ ] **Clean up.** Delete the test accounts if you made any by hand (account deletion is deferred, so via SQL on the host per `runbook-mbd-17-auth.md`).
