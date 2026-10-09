# Runbook: MBD-18 Email verification with Amazon SES

The api now emails a one-time verification link at signup through Amazon SES, and login answers
**403 "Email not verified"** until that link is visited. This runbook sets up everything on the AWS side
in the **AWS console**, prepares the host, and records the high-risk person check. Human-run (`risk: high`).

Sign in to the console as root or an admin user and pick region **US East (N. Virginia) us-east-1** in the
top-right selector before every section. SES identities are **per region**: an identity verified in another
region does not exist for the api.

| Item | Value |
| ---- | ----- |
| Sending domain | `musicboxd.com.br` (SES domain identity, Easy DKIM, RSA 2048) |
| From address | `no-reply@musicboxd.com.br` (no mailbox needed: the domain identity covers every address on it) |
| Verification link | `https://musicboxd.com.br/verificar-email?token=...` (SPA page that calls `GET /api/v1/auth/verify`, MBD-63), valid 24 h, single use |
| Credentials | host role `musicboxd-host-role`, new inline policy `musicboxd-ses` (no static keys, AD-11) |
| Host config | two new lines in `/etc/musicboxd/api.env` (section 5); SES itself is on by default in the api |
| SES mode | **sandbox** until MBD-24: sends only to verified recipient addresses, 200 emails/24 h, 1 email/s |

**Scope split with MBD-24.** This ticket verifies the domain with DKIM because SES cannot send from an
unverified domain, and DKIM is what lets Gmail accept the mail (DMARC alignment). MBD-24 then adds the
custom MAIL FROM domain (SPF), a DMARC record, and requests production access (sandbox exit). Until MBD-24
lands, only the addresses you verify in section 2 can receive verification email, so **real sign-ups in
production cannot complete yet**. That is the known, accepted gap from the epic.

**Cost bound (AD-10).** SES outbound costs USD 0.10 per 1,000 emails. The sandbox caps sending at 200
emails/24 h, so the worst case is about USD 0.02/day (under USD 1/month). After MBD-24 the cap rises, and
the per-IP limit on resend (3 per 15 min, this ticket) plus MBD-23's per-user limits bound it. The MBD-12
budget alerts cover the account total.

**Order matters.** Sections 1–5 must be done **before** the MBD-18 branch is merged. The api refuses to
start without the new `api.env` lines, and a deploy without them fails its health check and rolls back
(the same failure mode as the MBD-17 JWT secret).

## 1. Verify the sending domain (console: SES)

1. Console search bar → **Amazon Simple Email Service** → left menu **Configuration → Identities** →
   **Create identity**.
2. **Identity details**
   - Identity type: **Domain**
   - Domain: `musicboxd.com.br`
   - **Assign a default configuration set**: leave unchecked.
   - **Use a custom MAIL FROM domain**: leave unchecked (MBD-24 does this).
3. **Verifying your domain → Advanced DKIM settings**
   - Identity type: **Easy DKIM**
   - DKIM signing key length: **RSA_2048_BIT**
   - **Publish DNS records to Route53**: check it only if `musicboxd.com.br` is a Route 53 hosted zone in
     this account. Otherwise leave it unchecked.
   - DKIM signatures: **Enabled**
4. **Tags**: optional, `project = musicboxd`.
5. **Create identity**.

The identity page opens with **Identity status: Verification pending** and a **DomainKeys Identified Mail
(DKIM)** panel listing **three CNAME records**. Each one looks like:

| Type | Name | Value |
| ---- | ---- | ----- |
| CNAME | `abc123...xyz._domainkey.musicboxd.com.br` | `abc123...xyz.dkim.amazonses.com` |

**Publish the three CNAMEs at the DNS provider of `musicboxd.com.br`** (where the apex A record to the
Elastic IP lives, see `runbook-aws-host.md`). Use the **Download .csv record set** button so nothing is
mistyped.

- **Registro.br** (DNS mode "Utilizar os servidores DNS do Registro.br"): domain → **DNS** → **Editar zona**
  → **Nova entrada** three times, type **CNAME**. In the name field enter only the part **before**
  `.musicboxd.com.br` (e.g. `abc123...xyz._domainkey`). Some panels append the domain themselves, and
  entering the full name produces `..._domainkey.musicboxd.com.br.musicboxd.com.br`. **Salvar**.
- **Route 53** (box unchecked above): Route 53 → **Hosted zones** → `musicboxd.com.br` → **Create record**
  three times: record name = the part before the domain, type **CNAME**, value = the `dkim.amazonses.com`
  target, TTL 300.
- **Any other provider**: three CNAME records, same rule for the name field.

Do **not** touch the existing A record, and do not add an MX record (we do not receive mail).

**Wait for verification (V1).** DNS can take from minutes to a few hours. Refresh the identity page until:

- **Identity status: Verified**
- **DKIM configuration: Successful**

If after 72 h it is still pending, SES marks it failed. Check the names with
`nslookup -type=CNAME <name> 8.8.8.8` from your machine: each must answer with its `dkim.amazonses.com`
target. Then fix the records and click **Retry DKIM verification**.

## 2. Verify test recipients (console: SES, sandbox only)

In the sandbox, SES also refuses to deliver to any address that is not a verified identity. Verify the
address you will test with, plus two Gmail plus-addresses. Each registration needs a fresh email, because
account deletion is deferred (AD-12).

For each of `you@gmail.com`, `you+mbd18a@gmail.com`, `you+mbd18b@gmail.com` (your real Gmail address):

1. **Configuration → Identities → Create identity** → Identity type **Email address** → the address →
   **Create identity**.
2. Open the email from `no-reply-aws@amazon.com` ("Amazon Web Services – Email Address Verification
   Request in region US East (N. Virginia)"), which Gmail delivers to your main inbox, and click the link
   **within 24 h**.
3. Back in the console the identity shows **Verified**.

**Check the account status (V2).** Left menu **Account dashboard**:

- The banner says the account is in the **sandbox**. That is expected until MBD-24.
- **Sending quota**: 200 per 24 hours, **Maximum send rate**: 1 per second.
- **Account status / sending**: **Enabled**. If it says paused, stop and open a support case.

## 3. Let the host role send as no-reply (console: IAM)

1. Console search bar → **IAM** → **Roles** → `musicboxd-host-role`.
2. **Permissions** tab → **Add permissions** → **Create inline policy** → **JSON** tab.
3. Replace everything with the policy below. Put your 12-digit account ID in place of `<ACCOUNT_ID>`
   (top-right menu → the account ID with the copy icon; also in `aws-values.local.md`):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SendAsNoReplyOnly",
      "Effect": "Allow",
      "Action": "ses:SendEmail",
      "Resource": "arn:aws:ses:us-east-1:<ACCOUNT_ID>:identity/*",
      "Condition": {
        "StringEquals": { "ses:FromAddress": "no-reply@musicboxd.com.br" }
      }
    }
  ]
}
```

4. **Next** → Policy name `musicboxd-ses` → **Create policy**.

Why `identity/*` and not only the domain: in the sandbox, SES also checks the **recipient** identities
against the policy, so a domain-only resource fails with `not authorized to perform ses:SendEmail on
resource ...identity/you@gmail.com`. The `ses:FromAddress` condition is what keeps it least-privilege: the
host can send only as `no-reply@musicboxd.com.br`. The same JSON is committed as
`deploy/aws/host-ses-policy.json`.

**Check (V3).** The role's **Permissions policies** list now shows `AmazonSSMManagedInstanceCore`,
`musicboxd-s3` and `musicboxd-ses`, and nothing else.

## 4. Let containers reach the role credentials (console: EC2)

Until now only the host itself (backup scripts, AWS CLI) used the role. The api runs **inside a Docker
container**, and with IMDSv2 a container on a bridge network is one network hop further away. With a
metadata hop limit of 1, the SDK inside the container times out fetching credentials and every email
fails with `Unable to load credentials from any of the providers in the chain`.

1. Console → **EC2** → **Instances** → check `musicboxd-host`.
2. **Actions** → **Instance settings** → **Modify instance metadata options**.
3. Set:
   - Instance metadata service: **Enable**
   - IMDSv2: **Required** (keep it; do not loosen to Optional)
   - **Metadata response hop limit: `2`**
   - Allow tags in instance metadata: leave as is
4. **Save**.

No reboot is needed. Check it (V4) on the instance's **Details** tab: **IMDSv2: Required**. The hop limit
shows when you reopen the same dialog.

**Security trade-off.** With a hop limit of 2, every container on the host can reach the instance role's
credentials, not only the api: that includes the internet-facing nginx/web container and postgres. The role
carries `musicboxd-s3` (backups and images) and `musicboxd-ses`, so a compromised container or an SSRF in the
api could read them. This is accepted for the MVP (it is the standard AWS guidance for containers). The
follow-up hardening is an iptables rule in the `DOCKER-USER` chain that blocks `169.254.169.254` from every
container except the api.

## 5. Prepare the host (console: EC2 → Connect → Session Manager)

Open a shell: EC2 → Instances → `musicboxd-host` → **Connect** → **Session Manager** → **Connect**.
Paste with **Ctrl+Shift+V**.

**5a. Prove the container path to SES works before any code ships (V5).** This sends a real email through
the same route the api will use (a container on the stack's network, role credentials over IMDS, the
`musicboxd-ses` policy). The api sends through Spring Cloud AWS, which calls the same `SendEmail`
permission (the CLI uses the v2 endpoint of the same action). Replace the recipient with **one of the addresses verified in section 2**:

```bash
TO=you@gmail.com
sudo docker run --rm --network musicboxd_internal public.ecr.aws/aws-cli/aws-cli:2.27.0 \
  sts get-caller-identity --region us-east-1
# Arn must be arn:aws:sts::<ACCOUNT_ID>:assumed-role/musicboxd-host-role/i-...

sudo docker run --rm --network musicboxd_internal public.ecr.aws/aws-cli/aws-cli:2.27.0 \
  sesv2 send-email --region us-east-1 \
  --from-email-address no-reply@musicboxd.com.br \
  --destination "ToAddresses=$TO" \
  --content 'Simple={Subject={Data="Musicboxd SES test",Charset=UTF-8},Body={Text={Data="MBD-18 runbook section 5a",Charset=UTF-8}}}'
# expect: { "MessageId": "..." }
```

The email must arrive (check **Spam** too). In Gmail open it → **⋮** → **Show original**:
`DKIM: 'PASS' with domain musicboxd.com.br`.

If `musicboxd_internal` does not exist, list networks with `sudo docker network ls` and use the one ending
in `_internal`.

| Error | Cause | Fix |
| ----- | ----- | ----- |
| `Unable to locate credentials` / timeout on `sts` | hop limit still 1 | section 4 |
| `AccessDenied ... not authorized to perform: ses:SendEmail` | policy missing, typo in account ID, or From differs | section 3 |
| `MessageRejected: Email address is not verified ... you@gmail.com` | recipient not verified (sandbox) | section 2 |
| `MessageRejected: Email address is not verified ... no-reply@...` | domain identity not Verified yet, or created in another region | section 1 |

**5b. Add the api settings (before the merge).**

```bash
sudo grep -q '^MUSICBOXD_MAIL_FROM=' /etc/musicboxd/api.env || sudo sh -c 'cat >> /etc/musicboxd/api.env <<EOF
MUSICBOXD_MAIL_FROM=no-reply@musicboxd.com.br
MUSICBOXD_PUBLIC_BASE_URL=https://musicboxd.com.br
EOF'
sudo grep -v '^MUSICBOXD_JWT_SECRET=' /etc/musicboxd/api.env
# expect exactly the two lines above (the JWT secret is hidden on purpose)
sudo ls -l /etc/musicboxd/api.env     # still -rw------- root root
```

`MUSICBOXD_PUBLIC_BASE_URL` has no trailing slash. It is the start of every verification link, so a typo
here sends people to a dead link. `MUSICBOXD_MAIL_FROM` must be exactly the address the IAM condition in
section 3 allows. Do **not** add `SPRING_CLOUD_AWS_SES_ENABLED` here: that switch exists only to turn SES off
in local dev, and production relies on its default (on).

## 6. Merge and deploy

Merge the MBD-18 PR to `main` and wait for GitHub → Actions → **Publish images** to go green. If the
`deploy` job fails on the health check, read the api log in the host terminal:

```bash
cd /opt/musicboxd
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml logs api --tail 50
# "...mail-from (env MUSICBOXD_MAIL_FROM) must be set" or "...link-base-url (env MUSICBOXD_PUBLIC_BASE_URL) must be set" → section 5b
```

## 7. Acceptance test (from your own machine)

Use a **verified** address you have **not** registered yet (`you+mbd18a@gmail.com`). Git Bash or WSL:

```bash
D=https://musicboxd.com.br
E=you+mbd18a@gmail.com
curl -s -w '\n%{http_code}\n' -X POST $D/api/v1/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"$E\",\"password\":\"correct-horse\",\"username\":\"mbd18_a\"}"          # 201
curl -s -w '\n%{http_code}\n' -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$E\",\"password\":\"correct-horse\"}"                                    # 403, "Email not verified"
```

Open the email **"Confirme seu email no Musicboxd"** (check Spam) and click the link. It opens the app page
`/verificar-email`, which shows **"Tudo certo!"** with an **Entrar** button (MBD-63). For the raw API answer instead,
`curl -s "$D/api/v1/auth/verify?token=<token from the link>"` returns `{"status":"verified"}`. Then:

```bash
curl -s -w '\n%{http_code}\n' -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$E\",\"password\":\"correct-horse\"}"                                    # 200, accessToken
```

That is the story's acceptance criterion (V6).

## 8. High-risk person check: unverified is blocked on every path (V7)

The story requires a person to confirm the block is real, not just one code path. Use the second address
(`you+mbd18b@gmail.com`) and **do not click its link until step 6 below**.

```bash
E=you+mbd18b@gmail.com
curl -s -o /dev/null -w '%{http_code}\n' -X POST $D/api/v1/auth/register -H 'Content-Type: application/json' \
  -d "{\"email\":\"$E\",\"password\":\"correct-horse\",\"username\":\"mbd18_b\"}"          # 201
```

1. **Right password, unverified** → 403:
   `curl -s -w '\n%{http_code}\n' -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$E\",\"password\":\"correct-horse\"}"`
2. **Upper-case / padded email, unverified** → still 403 (the gate is not tied to one spelling):
   same command with `"email":"  YOU+MBD18B@GMAIL.COM "`.
3. **Wrong password, unverified** → **401**, the same body as for an unknown email. Verification state must
   not leak to someone without the password.
4. **No token reaches a protected route**: there is no token to try. Confirm in the code that only login
   issues tokens. In the repo: `grep -rn "tokens.issue(" api/src/main/java` lists only
   `AuthController.login`, and `AccountService.authenticate` throws `EmailNotVerifiedException` before
   returning an id.
5. **Database state** (host terminal):

   ```bash
   cd /opt/musicboxd
   sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
     sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
       SELECT a.email, a.email_verified_at, t.expires_at, t.consumed_at, length(t.token_hash) AS hash_len
       FROM accounts.accounts a JOIN accounts.email_verification_tokens t ON t.account_id = a.id
       WHERE a.email LIKE '"'"'%mbd18%'"'"' ORDER BY t.created_at"'
   # mbd18a: email_verified_at set, consumed_at set
   # mbd18b: email_verified_at empty, consumed_at empty, expires_at ~24 h after signup
   # hash_len 64 (SHA-256 hex): the raw token from the link is NOT what is stored
   ```

6. **Resend** → 202, and a **second** email arrives:
   `curl -s -o /dev/null -w '%{http_code}\n' -X POST $D/api/v1/auth/verification-email -H 'Content-Type: application/json' -d "{\"email\":\"$E\"}"`.
   Click the link in the **first** email: the page must show **"Link inválido ou expirado"** (the API answers
   400; resend replaced it). Click the link in the **second** email: **"Tudo certo!"**. Click it again:
   **"Tudo certo!"** (idempotent, so mail scanners that pre-open links do not break it). Login now → 200.
7. **Pre-MBD-18 accounts were not locked out**: the MBD-17 smoke account (`smoke+1@example.com`) still
   logs in with 200. The V2 migration marks every existing account as verified.

Record who did the check in the table below.

## Verification results

| Check | Expected | Result | Date |
| ----- | -------- | ------ | ---- |
| V1 domain identity (section 1) | Verified, DKIM Successful | `TODO` | |
| V2 sandbox status (section 2) | 3 recipient identities Verified; quota 200/24 h; sending enabled | `TODO` | |
| V3 IAM (section 3) | `musicboxd-ses` present with the FromAddress condition | `TODO` | |
| V4 IMDS (section 4) | IMDSv2 required, hop limit 2 | `TODO` | |
| V5 container send (section 5a) | caller is the host role; email received; DKIM PASS | `TODO` | |
| V6 acceptance (section 7) | 403 before the link, 200 after | `TODO` | |
| V7 person check (section 8), checked by: `TODO name` | all 7 steps as described | `TODO` | |

## Day-to-day

- **Bounces and complaints.** In the sandbox only verified addresses receive mail, so neither happens. Before
  MBD-24 requests production access, AWS will ask how bounces and complaints are handled. That ticket adds
  the SNS notifications.
- **Logs.** A failed send never fails the signup. The api logs
  `Verification email for account <id> failed` and the person can use resend. Check with
  `docker compose ... logs api | grep "Verification email"`.
- **Raising the limit.** The sandbox cap (200/24 h) is per account and region. Watch **Account dashboard →
  Sending statistics** if testing heavily.

## Rollback

There is no config switch that turns verification off while MBD-18 code is running. To roll back the code,
redeploy the previous image (`sudo ./deploy.sh sha-<previous sha>` in `/opt/musicboxd`). Rolling back the
image removes the login gate until MBD-18 is redeployed: the older code ignores `email_verified_at`, so
unverified accounts can log in meanwhile. The V2 migration stays applied, and the older code ignores the new
column and table.
The two `api.env` lines are harmless to the older code. The AWS pieces (identity, policy, hop limit) can
stay. They are needed again on the next deploy.
