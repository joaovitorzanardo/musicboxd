# MBD-9 Production Compose Stack Behind nginx TLS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the `api` / `postgres` / `nginx` Compose stack on the MBD-8 EC2 host, serving the SPA over HTTPS on a placeholder domain with a Let's Encrypt certificate that renews automatically and reloads nginx.

**Architecture:** A new `deploy/docker-compose.prod.yml` (images from GHCR, secrets from env files on the host, nginx publishes 80 and 443) sits next to the untouched local-dev compose file. nginx gets a prod config template rendered from `DOMAIN` by the stock nginx image's envsubst entrypoint, bind-mounted over the baked-in HTTP-only config. Certificates are issued by certbot (HTTP-01, webroot) from a bootstrap script run before the first `up`, and renewed by a host systemd timer that runs `certbot renew` and then reloads nginx.

**Tech Stack:** Docker Compose v2, nginx 1.27-alpine, certbot/certbot (arm64), systemd timer on Amazon Linux 2023, GHCR images from MBD-7, bash.

**Spec:** `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-production-compose-stack-behind-nginx-tls.md` (Jira MBD-9); `ARCHITECTURE-SPINE.md` AD-11 and Stack table (`_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`).

## Global Constraints

- Only ports 80 and 443 are public; Postgres reachable only on the internal Docker network, no published port (AD-11).
- Secrets live in env files on the host outside the repository; nothing secret committed (AD-11).
- No static AWS keys anywhere (AD-11); GHCR pull uses a read-only token entered on the host, never committed.
- Images are linux/arm64 (t4g.small); nginx image tag pinned (`nginx:1.27-alpine`, same as `web/Dockerfile`); Compose v2.
- Certificate renewal is automatic and reloads nginx (AD-11). The expiry alarm in AD-11 is NOT part of this ticket's AC; do not build it here.
- Domain is a placeholder, supplied only via `DOMAIN` in `/etc/musicboxd/stack.env`. Swapping it for a real domain, and any SPA/CORS origin change, is explicitly out of scope. The SPA and API share one origin through nginx (AD-8), so there is no CORS config to touch.
- Shell access to the host is SSM only (no SSH key pair, MBD-8).
- Local-dev `deploy/docker-compose.yml` and `deploy/nginx/nginx.conf` keep working unchanged.
- MBD-10 (CD) will later own pulling new image tags; this ticket only needs `IMAGE_TAG` to be a variable defaulting to `latest`.

## Review Focus

- Cert files missing at first boot: nginx must not be started with a 443 server block before the cert exists (it crash-loops). Bootstrap script must refuse to start the stack without a cert and say why.
- Renewal when nothing is due: `certbot renew` is a no-op; the timer must not fail, and nginx must only be reloaded when a cert actually renewed.
- `/.well-known/acme-challenge/` must stay reachable over plain HTTP (not redirected to HTTPS) or renewal breaks on day 60.
- SPA deep links (`/albums/123`) must still fall back to `index.html` over HTTPS, and `/api/` must still proxy with `X-Forwarded-Proto: https`.
- Let's Encrypt rate limits: bootstrap and tests use `--staging`/`--dry-run` before the real issuance so a typo does not burn the 5-failures-per-hour limit.
- GHCR packages are private by default: `docker compose pull` on the host fails with `denied` unless logged in or the packages are public.

---

## File Structure

- Create `deploy/nginx/nginx.prod.conf.template`: HTTPS server, HTTP→HTTPS redirect, ACME webroot, SPA fallback, `/api/` proxy.
- Create `deploy/docker-compose.prod.yml`: api, postgres, nginx (+ `certbot` profile service used only via `run`).
- Create `deploy/prod.env.example`: documents `stack.env` and `postgres.env` shape (no real secrets).
- Create `deploy/certbot/init-cert.sh`: first issuance.
- Create `deploy/certbot/renew-cert.sh`: renew + conditional nginx reload.
- Create `deploy/systemd/musicboxd-certbot-renew.service` and `.timer`.
- Create `deploy/tests/test-nginx-conf.sh` and `deploy/tests/test-compose-config.sh`: config validation, runnable locally with Docker.
- Modify `deploy/runbook-aws-host.md`: add MBD-9 section (host setup, verification, results table). This file is currently untracked from MBD-8; commit it as part of this work only if MBD-8 has not already committed it, otherwise just append.

---

### Task 1: Prod nginx config (TLS, redirect, ACME, SPA, API proxy)

**Files:**
- Create: `deploy/nginx/nginx.prod.conf.template`
- Test: `deploy/tests/test-nginx-conf.sh`

**Interfaces:**
- Consumes: env var `DOMAIN` (rendered by the nginx image entrypoint); cert at `/etc/letsencrypt/live/${DOMAIN}/{fullchain,privkey}.pem`; webroot dir `/var/www/certbot`; upstream host `api:8080`.
- Produces: the template path `deploy/nginx/nginx.prod.conf.template` that Task 2 mounts at `/etc/nginx/templates/default.conf.template`.

- [ ] **Step 1: Write the failing test**

```bash
#!/usr/bin/env bash
# deploy/tests/test-nginx-conf.sh
# Renders the prod template with a throwaway cert and runs `nginx -t` on it.
set -euo pipefail
cd "$(dirname "$0")/../.."

DOMAIN=example.test
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/live/$DOMAIN"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$DOMAIN" \
  -keyout "$TMP/live/$DOMAIN/privkey.pem" -out "$TMP/live/$DOMAIN/fullchain.pem" 2>/dev/null

# --add-host: nginx -t resolves the `api` upstream at load time.
docker run --rm --add-host api:127.0.0.1 \
  -e DOMAIN="$DOMAIN" -e NGINX_ENVSUBST_FILTER=DOMAIN \
  -v "$PWD/deploy/nginx/nginx.prod.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  -v "$TMP:/etc/letsencrypt:ro" \
  nginx:1.27-alpine sh -c '/docker-entrypoint.d/20-envsubst-on-templates.sh >/dev/null && nginx -t && nginx -T 2>/dev/null' > "$TMP/out.txt"

grep -q 'listen 443 ssl' "$TMP/out.txt"
grep -q "ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem" "$TMP/out.txt"
grep -q 'location /.well-known/acme-challenge/' "$TMP/out.txt"
grep -q 'return 301 https://' "$TMP/out.txt"
grep -q 'try_files $uri $uri/ /index.html' "$TMP/out.txt"
grep -q 'proxy_set_header X-Forwarded-Proto $scheme' "$TMP/out.txt"
echo "nginx prod config OK"
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bash deploy/tests/test-nginx-conf.sh`
Expected: FAIL (template file not found / `nginx -t` error).

- [ ] **Step 3: Write the template**

```nginx
# deploy/nginx/nginx.prod.conf.template
# Rendered by the nginx image entrypoint (envsubst, DOMAIN only) into
# /etc/nginx/conf.d/default.conf, replacing the HTTP-only config baked in by web/Dockerfile.

server {
    listen 80;
    server_name ${DOMAIN};

    # Let's Encrypt HTTP-01 challenges must stay on plain HTTP.
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl;
    http2 on;
    server_name ${DOMAIN};

    ssl_certificate     /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DOMAIN}/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    # Single origin for SPA + API (AD-8); api paths already start with /api/v1 (AD-7).
    location /api/ {
        proxy_pass http://api:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

No HSTS on purpose: the domain is a placeholder and HSTS would stick to it in browsers.

- [ ] **Step 4: Run it to verify it passes**

Run: `bash deploy/tests/test-nginx-conf.sh`
Expected: `nginx prod config OK`

- [ ] **Step 5: Commit**

```bash
git add deploy/nginx/nginx.prod.conf.template deploy/tests/test-nginx-conf.sh
git commit -m "Add prod nginx TLS config template (MBD-9)"
```

---

### Task 2: Production Compose file and env contract

**Files:**
- Create: `deploy/docker-compose.prod.yml`
- Create: `deploy/prod.env.example`
- Test: `deploy/tests/test-compose-config.sh`

**Interfaces:**
- Consumes: Task 1's template; images `ghcr.io/${GHCR_OWNER}/musicboxd-api` and `musicboxd-web` (published by `.github/workflows/publish-images.yml`, tags `latest` and `sha-<long>`).
- Produces: services `api`, `postgres`, `nginx`, and a `certbot` service (profile `tools`, only for `docker compose run`). Named volumes `letsencrypt` and `certbot_www`. Env files `/etc/musicboxd/stack.env` (`DOMAIN`, `GHCR_OWNER`, `IMAGE_TAG`, `CERTBOT_EMAIL`) and `/etc/musicboxd/postgres.env` (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`). Tasks 3 and 4 call these by name.

- [ ] **Step 1: Write the failing test**

```bash
#!/usr/bin/env bash
# deploy/tests/test-compose-config.sh
set -euo pipefail
cd "$(dirname "$0")/.."

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
printf 'DOMAIN=example.test\nGHCR_OWNER=someone\nIMAGE_TAG=latest\nCERTBOT_EMAIL=a@b.c\n' > "$TMP/stack.env"
printf 'POSTGRES_DB=musicboxd\nPOSTGRES_USER=musicboxd\nPOSTGRES_PASSWORD=x\n' > "$TMP/postgres.env"

OUT=$(MUSICBOXD_ENV_DIR="$TMP" docker compose --env-file "$TMP/stack.env" -f docker-compose.prod.yml config)

grep -q 'ghcr.io/someone/musicboxd-api:latest' <<<"$OUT"
grep -q 'ghcr.io/someone/musicboxd-web:latest' <<<"$OUT"
# Only nginx publishes ports, and only 80/443.
PUBLISHED=$(grep -E '^\s+published:' <<<"$OUT" | tr -d ' "' | sort | tr '\n' ' ')
[ "$PUBLISHED" = "published:443 published:80 " ] || { echo "unexpected published ports: $PUBLISHED"; exit 1; }
! grep -q 'POSTGRES_PASSWORD: musicboxd' <<<"$OUT"   # no hardcoded dev creds
echo "prod compose config OK"
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bash deploy/tests/test-compose-config.sh`
Expected: FAIL, "docker-compose.prod.yml: no such file".

- [ ] **Step 3: Write the compose file and example env**

```yaml
# deploy/docker-compose.prod.yml
# Run on the EC2 host from /opt/musicboxd with:
#   docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d
services:
  api:
    image: ghcr.io/${GHCR_OWNER}/musicboxd-api:${IMAGE_TAG:-latest}
    restart: unless-stopped
    env_file: ${MUSICBOXD_ENV_DIR:-/etc/musicboxd}/postgres.env
    networks: [internal]
    # No ports published: reached only through nginx (AD-11).
    healthcheck:
      test: ['CMD', 'bash', '-c', 'exec 3<>/dev/tcp/127.0.0.1/8080']
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 30s

  postgres:
    image: postgres:18
    restart: unless-stopped
    env_file: ${MUSICBOXD_ENV_DIR:-/etc/musicboxd}/postgres.env
    volumes:
      - postgres_data:/var/lib/postgresql
    networks: [internal]
    # No ports published (AD-11).

  nginx:
    image: ghcr.io/${GHCR_OWNER}/musicboxd-web:${IMAGE_TAG:-latest}
    restart: unless-stopped
    ports:
      - '80:80'
      - '443:443'
    environment:
      DOMAIN: ${DOMAIN}
      NGINX_ENVSUBST_FILTER: DOMAIN
    volumes:
      - ./nginx/nginx.prod.conf.template:/etc/nginx/templates/default.conf.template:ro
      - letsencrypt:/etc/letsencrypt:ro
      - certbot_www:/var/www/certbot:ro
    depends_on:
      api:
        condition: service_healthy
    networks: [internal]

  # Never started by `up`; used via `docker compose run --rm certbot ...`.
  certbot:
    image: certbot/certbot:latest
    profiles: [tools]
    volumes:
      - letsencrypt:/etc/letsencrypt
      - certbot_www:/var/www/certbot

networks:
  internal:

volumes:
  postgres_data:
  letsencrypt:
  certbot_www:
```

The `certbot` service publishes nothing; `init-cert.sh` passes `-p 80:80` to `docker compose run` for standalone issuance (Task 3).

```bash
# deploy/prod.env.example  (copy to /etc/musicboxd/stack.env and postgres.env, chmod 600, owned by root)
# --- stack.env ---
DOMAIN=musicboxd.example.test      # placeholder domain, A record -> the Elastic IP
GHCR_OWNER=your-github-user-lowercase
IMAGE_TAG=latest
CERTBOT_EMAIL=you@example.com
# --- postgres.env ---
POSTGRES_DB=musicboxd
POSTGRES_USER=musicboxd
POSTGRES_PASSWORD=change-me-generated-with-openssl-rand-base64-24
```

- [ ] **Step 4: Run it to verify it passes**

Run: `bash deploy/tests/test-compose-config.sh`
Expected: `prod compose config OK`

- [ ] **Step 5: Commit**

```bash
git add deploy/docker-compose.prod.yml deploy/prod.env.example deploy/tests/test-compose-config.sh
git commit -m "Add production Compose stack with env-file secrets (MBD-9)"
```

---

### Task 3: Certificate bootstrap and automatic renewal

**Files:**
- Create: `deploy/certbot/init-cert.sh`
- Create: `deploy/certbot/renew-cert.sh`
- Create: `deploy/systemd/musicboxd-certbot-renew.service`
- Create: `deploy/systemd/musicboxd-certbot-renew.timer`
- Test: shellcheck + `bash -n` locally; certbot `--dry-run` on the host (Task 4)

**Interfaces:**
- Consumes: Task 2's `certbot` service, volumes, `/etc/musicboxd/stack.env`.
- Produces: `init-cert.sh [--staging]` (exit 0 = cert present in the `letsencrypt` volume); `renew-cert.sh` (exit 0 whether or not anything renewed; reloads nginx only on renewal); installed timer `musicboxd-certbot-renew.timer` (twice daily).

- [ ] **Step 1: Write the failing check**

Run: `shellcheck deploy/certbot/*.sh`
Expected: FAIL, files do not exist. (If shellcheck is unavailable, `docker run --rm -v "$PWD:/mnt" koalaman/shellcheck:stable /mnt/deploy/certbot/*.sh`.)

- [ ] **Step 2: Write `init-cert.sh`**

```bash
#!/usr/bin/env bash
# Issues the first certificate with certbot standalone on port 80, BEFORE nginx is started
# (nginx's 443 block cannot load without the cert files). Run on the host from /opt/musicboxd.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
set -a; . "$ENV_FILE"; set +a
: "${DOMAIN:?DOMAIN missing in $ENV_FILE}" "${CERTBOT_EMAIL:?CERTBOT_EMAIL missing in $ENV_FILE}"

STAGING=()
[ "${1:-}" = "--staging" ] && STAGING=(--staging)

COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)

if "${COMPOSE[@]}" run --rm --no-deps --entrypoint sh certbot -c "test -f /etc/letsencrypt/live/$DOMAIN/fullchain.pem"; then
  echo "Certificate for $DOMAIN already present; nothing to do."
  exit 0
fi

# -p 80:80 here instead of in the compose file so `up` never publishes port 80 twice.
"${COMPOSE[@]}" run --rm --no-deps -p 80:80 certbot certonly --standalone \
  -d "$DOMAIN" --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email --non-interactive "${STAGING[@]}"

echo "Certificate issued${STAGING:+ (STAGING: browsers will not trust it; delete the volume and re-run without --staging)}."
```

- [ ] **Step 3: Write `renew-cert.sh`**

```bash
#!/usr/bin/env bash
# Renews due certificates over the webroot (nginx keeps serving) and reloads nginx only if one renewed.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${ENV_FILE:-/etc/musicboxd/stack.env}
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.prod.yml)
MARKER=/etc/letsencrypt/.renewed   # inside the certbot container's volume

"${COMPOSE[@]}" run --rm --no-deps --entrypoint sh certbot -c "rm -f $MARKER"
"${COMPOSE[@]}" run --rm --no-deps certbot renew --webroot -w /var/www/certbot \
  --deploy-hook "touch $MARKER" "$@"

if "${COMPOSE[@]}" run --rm --no-deps --entrypoint sh certbot -c "test -f $MARKER"; then
  "${COMPOSE[@]}" exec -T nginx nginx -s reload
  echo "Certificate renewed; nginx reloaded."
else
  echo "No certificate due for renewal."
fi
```

`"$@"` lets the host verification pass `--dry-run`. Note `--dry-run` does not run deploy hooks, so a dry run reports "No certificate due"; the reload path is proven separately with `--force-renewal` against `--staging` in Task 4.

- [ ] **Step 4: Write the systemd units**

```ini
# deploy/systemd/musicboxd-certbot-renew.service
[Unit]
Description=Renew musicboxd TLS certificate and reload nginx
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=/opt/musicboxd
ExecStart=/opt/musicboxd/certbot/renew-cert.sh
```

```ini
# deploy/systemd/musicboxd-certbot-renew.timer
[Unit]
Description=Twice-daily musicboxd certificate renewal check

[Timer]
OnCalendar=*-*-* 03,15:17:00
RandomizedDelaySec=30m
Persistent=true

[Install]
WantedBy=timers.target
```

- [ ] **Step 5: Run the check to verify it passes**

Run: `chmod +x deploy/certbot/*.sh && shellcheck deploy/certbot/*.sh && bash -n deploy/certbot/init-cert.sh && bash -n deploy/certbot/renew-cert.sh`
Expected: no output, exit 0.

- [ ] **Step 6: Commit**

```bash
git add deploy/certbot deploy/systemd
git commit -m "Add certbot bootstrap and renewal timer with nginx reload (MBD-9)"
```

---

### Task 4: Deploy to the host (HITL, over SSM)

This task is manual and needs the user: DNS, secrets, and a GHCR token cannot be done by an agent. Record each outcome in the runbook as you go.

**Files:**
- Modify: `deploy/runbook-aws-host.md` (append `## MBD-9: Compose stack and TLS` section)

**Interfaces:**
- Consumes: everything above; Elastic IP from the MBD-8 runbook inventory; images already on GHCR from MBD-7 (check Actions run for `main` succeeded and the packages exist).
- Produces: running stack, installed timer, runbook section Task 5 fills in.

- [ ] **Step 1: Merge this branch to `main`** so CI has published images containing the current `web/Dockerfile`. (The prod template is bind-mounted, so image content does not depend on this branch, but `deploy/` files reach the host from the repo.) Confirm `ghcr.io/<owner>/musicboxd-api:latest` and `musicboxd-web:latest` exist.

- [ ] **Step 2: Get a placeholder domain pointing at the Elastic IP.** Recommended: a free DuckDNS subdomain (`musicboxd-xyz.duckdns.org`) with the A record set to the Elastic IP. Avoid `nip.io`/`sslip.io`: their shared Let's Encrypt rate limits are often exhausted. Check: `nslookup <domain>` returns the Elastic IP.

- [ ] **Step 3: On the host (SSM session), install the repo files and env files**

```bash
sudo dnf install -y git
sudo git clone https://github.com/<owner>/musicboxd.git /opt/musicboxd-src
sudo mkdir -p /opt/musicboxd && sudo cp -r /opt/musicboxd-src/deploy/. /opt/musicboxd/
sudo install -d -m 700 /etc/musicboxd
# create /etc/musicboxd/stack.env and postgres.env from deploy/prod.env.example (drop the inline # comments);
# generate the password with: openssl rand -base64 24
sudo chmod 600 /etc/musicboxd/*.env
```

If the repo is private, use a read-only deploy key or token for the clone, or `scp`-equivalent via SSM `send-command`; do not leave the token on disk.

- [ ] **Step 4: Authenticate to GHCR (only if packages are private)**

```bash
echo "<read:packages PAT>" | sudo docker login ghcr.io -u <github-user> --password-stdin
```

Expected: `Login Succeeded`. Alternative: make both packages public and skip this step.

- [ ] **Step 5: Dry-run issuance against Let's Encrypt staging, then issue for real**

```bash
cd /opt/musicboxd
sudo ss -ltnp | grep ':80 ' || echo "port 80 free"   # must be free; if you ran `up` early: `docker compose ... down` first
sudo ./certbot/init-cert.sh --staging     # expect: Certificate issued (STAGING...)
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml \
  run --rm -T --no-deps --entrypoint sh certbot -c "rm -rf /etc/letsencrypt/*"
sudo ./certbot/init-cert.sh               # expect: Certificate issued.
```

Do NOT run `docker compose up` before this step succeeds: nginx cannot load its 443 block without the cert and would crash-loop while holding port 80. Run all compose commands and scripts from `/opt/musicboxd` (the project name is pinned to `musicboxd`, but the template and scripts are copied there). The domain must have no AAAA record: Let's Encrypt prefers IPv6 for HTTP-01 and this host does not serve it.

- [ ] **Step 6: Start the stack**

```bash
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml pull
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml ps
```

Expected: `api` healthy, `postgres` and `nginx` running; `nginx` shows `0.0.0.0:80->80, 0.0.0.0:443->443`.

- [ ] **Step 7: Enable the daemon at boot and install the renewal timer**

```bash
sudo systemctl enable docker      # already done in MBD-8; harmless
sudo cp /opt/musicboxd/systemd/musicboxd-certbot-renew.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now musicboxd-certbot-renew.timer
systemctl list-timers musicboxd-certbot-renew.timer
```

Expected: timer listed with a NEXT time in the future.

- [ ] **Step 8: Commit the runbook section (steps taken, domain used, command outputs trimmed)**

```bash
git add deploy/runbook-aws-host.md
git commit -m "Document MBD-9 host deployment in runbook"
```

---

### Task 5: Verify the acceptance criterion and record results

**Files:**
- Modify: `deploy/runbook-aws-host.md` (results table in the MBD-9 section; also fill MBD-8 AC1 now that 80/443 are listening)

- [ ] **Step 1: HTTPS serves the SPA with a valid certificate** (from a machine outside AWS)

```bash
curl -sS -o /dev/null -w "%{http_code} %{ssl_verify_result}\n" https://<domain>/
curl -sS https://<domain>/ | grep -i '<div id="root"'     # SPA shell present
curl -sS -o /dev/null -w "%{http_code}\n" https://<domain>/some/deep/link   # 200 via try_files
curl -sSI http://<domain>/ | grep -i '^location: https://'                  # redirect
curl -sS -o /dev/null -w "%{http_code}\n" http://<domain>/.well-known/acme-challenge/x   # 404, not a redirect
echo | openssl s_client -connect <domain>:443 -servername <domain> 2>/dev/null | openssl x509 -noout -issuer -dates
```

Expected: `200 0`; root div found; `200`; redirect header; `404`; issuer Let's Encrypt, `notAfter` ~90 days out.

- [ ] **Step 2: API reachable through the proxy over HTTPS**

Run: `curl -sS -o /dev/null -w "%{http_code}\n" https://<domain>/api/v1/api-docs`
Expected: `200`.

- [ ] **Step 3: Renewal works end to end (on the host, no cert swap)**

`init-cert.sh` uses standalone port 80, which nginx holds once the stack is up, so do not re-run it here.

```bash
cd /opt/musicboxd
# 1. webroot renewal of the standalone-issued cert, through the running nginx
sudo ./certbot/renew-cert.sh --dry-run            # expect: no failure, "No certificate due..."

# 2. reload path: simulate "a cert just renewed" by touching the marker
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml \
  run --rm -T --no-deps --entrypoint sh certbot -c 'touch /etc/letsencrypt/.renewed'

# 3. run the real systemd unit (no TTY, systemd env and WorkingDirectory)
sudo systemctl start musicboxd-certbot-renew.service
systemctl status musicboxd-certbot-renew.service --no-pager   # expect status=0/SUCCESS
sudo journalctl -u musicboxd-certbot-renew.service --no-pager | tail   # expect "Certificate renewed; nginx reloaded."

# 4. run it again: marker was cleared after the successful reload
sudo systemctl start musicboxd-certbot-renew.service
sudo journalctl -u musicboxd-certbot-renew.service --no-pager | tail -3  # expect "No certificate due for renewal."
```


- [ ] **Step 4: Postgres not exposed, only 80/443 public**

```bash
nmap -Pn -p 22,80,443,5432 <elastic-ip>      # expect 80, 443 open; 22, 5432 filtered
```

- [ ] **Step 5: Reboot survival**

Run (SSM): `sudo reboot`, reconnect, wait 1-2 minutes (nginx can restart-loop briefly until `api` resolves), then `docker compose ... ps`, `systemctl list-timers musicboxd-certbot-renew.timer`, and Step 1's first curl.
Expected: stack back up on its own (`restart: unless-stopped`), HTTPS still `200 0`.

- [ ] **Step 6: Record results in the runbook table, then commit and update Jira**

```bash
git add deploy/runbook-aws-host.md
git commit -m "Record MBD-9 verification results"
```

Then transition MBD-9 per your normal flow. Remember the follow-ups this ticket deliberately leaves open: real domain swap, certificate expiry alarm (AD-11), CD (MBD-10), backups (MBD-11), rate limiting (MBD-13).

---

## Self-Review

- **Spec coverage:** compose stack on EC2 (Tasks 2, 4); nginx TLS via certbot on placeholder domain (1, 3, 4); auto-renew + nginx reload (3, 5); serves SPA over HTTPS with valid cert (5). Domain swap and expiry alarm explicitly excluded.
- **Placeholders:** `<domain>`, `<owner>`, `<elastic-ip>` are values only the user has; they come from the runbook inventory and Task 4 Step 2.
- **Consistency:** `DOMAIN`, `GHCR_OWNER`, `IMAGE_TAG`, `CERTBOT_EMAIL`, `MUSICBOXD_ENV_DIR`, `stack.env`, `postgres.env`, volumes `letsencrypt` / `certbot_www`, and the `certbot` service name are the same in every task. Task 2 step 3 removes the `certbot` `ports` block to match its own test.
- **Review Focus coverage:** missing cert → init-cert.sh runs before `up` (Task 4 step 5 ordering); no-op renew → Task 3 script + Task 5 step 3; ACME over HTTP → Task 1 test + Task 5 step 1; SPA deep link and proxy header → Task 1 test + Task 5 steps 1-2; rate limits → staging first (Task 4 step 5); GHCR auth → Task 4 step 4.
