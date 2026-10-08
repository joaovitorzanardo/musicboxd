# Runbook: AWS host (MBD-8)

Hand-provisioned in the AWS console, region **us-east-1**. Later tickets deploy onto this host
(MBD-9 Compose stack + TLS, MBD-31 image bucket, backup ticket, MBD-12 Budgets).

Fields marked `TODO` are filled in while provisioning. Every resource ID and setting must be recorded
here before the ticket is closed (AC 5).

## Resource inventory

| Resource                          | Value                                                                              |
| --------------------------------- | ---------------------------------------------------------------------------------- |
| Region                            | us-east-1                                                                          |
| Instance ID                       | `<INSTANCE_ID>`                                                              |
| AMI                               | Amazon Linux 2023 arm64 (`TODO ami-...`)                                           |
| Instance type                     | t4g.small                                                                          |
| Root volume                       | 20 GB gp3, encrypted (`TODO vol-...`, KMS key: `TODO` / aws/ebs default)           |
| Metadata options                  | IMDSv2 required (`HttpTokens=required`)                                            |
| VPC / subnet                      | default VPC `TODO vpc-...`, public subnet `TODO subnet-...`                        |
| Elastic IP                        | `TODO x.x.x.x` (`TODO eipalloc-...`)                                               |
| Security group                    | `musicboxd-host` (`TODO sg-...`)                                                   |
| IAM role / instance profile       | `musicboxd-host-role` (`TODO` ARN)                                                 |
| Images bucket (not created here)  | `musicboxd-images` (MBD-31)                                                        |
| Backups bucket (MBD-11)           | `musicboxd-backups` (fallback if name is taken: `TODO musicboxd-backups-<suffix>`); setup in `runbook-mbd-11-postgres-backups.md` |

## 0. Prerequisite: default VPC

Confirm the console region is us-east-1 (VPCs are per region). If the account has no default VPC there,
create one (free): VPC console, Actions, **Create default VPC**, or

```bash
aws ec2 create-default-vpc --region us-east-1
```

This creates the VPC, a public subnet per AZ, an internet gateway and the route table. Record the VPC and
subnet IDs in the inventory.

## 1. Security group `musicboxd-host`

Default VPC. Inbound rules:

| Type  | Port | Source      |
| ----- | ---- | ----------- |
| HTTP  | 80   | `0.0.0.0/0` |
| HTTP  | 80   | `::/0`      |
| HTTPS | 443  | `0.0.0.0/0` |
| HTTPS | 443  | `::/0`      |

Nothing else inbound. **Port 22 is never opened.** Outbound: allow all (default).

The console warns that `0.0.0.0/0` and `::/0` allow every IP address and recommends known IPs only. This is
accepted: 80 and 443 serve a public website, so there is no known set of client IPs. The warning is about
exposure of admin ports such as SSH, and those stay closed (shell access is SSM only).

## 2. IAM role and instance profile

Create role `musicboxd-host-role` (trusted entity: EC2), which creates the instance profile of the same name.

Managed policy attached:

- `AmazonSSMManagedInstanceCore` (AWS-managed, shell access via SSM; accepted exception to the role's scope)

Inline policy `musicboxd-s3`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ImagesObjects",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::musicboxd-images/*"
    },
    {
      "Sid": "BackupsObjects",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::musicboxd-backups/*"
    },
    {
      "Sid": "BackupsList",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::musicboxd-backups"
    }
  ]
}
```

Notes:

- `s3:PutObject` on the images bucket is also what lets the app presign uploads (presigned POST, AD-9).
- The bucket ARNs are fixed here before the buckets exist, so MBD-31 and the backup ticket converge on the same literal ARNs.
- If `musicboxd-backups` is taken globally, use the suffixed name, update the policy, and record it in the inventory above.
- No static keys, nothing else. SES is deliberately omitted and added by the later SES ticket.

## 3. EC2 instance

Launch settings:

- Name: `musicboxd-host`
- AMI: Amazon Linux 2023, **arm64**
- Type: **t4g.small**
- Key pair: **none** (no SSH)
- Network: default VPC, a public subnet, auto-assign public IP off (the Elastic IP is used)
- Security group: `musicboxd-host` only
- Storage: 20 GB **gp3**, **encrypted**
- Advanced: IAM instance profile `musicboxd-host-role`; Metadata version **V2 only (token required)**

Then allocate an Elastic IP and associate it with the instance.

## 4. Install Docker and Compose (by hand, over SSM)

```bash
aws ssm start-session --target <instance-id> --region us-east-1

sudo dnf update -y
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ssm-user

# Compose v2 plugin (arm64)
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

docker --version
docker compose version
```

Record the installed versions: Docker `TODO`, Compose `TODO`.

## 5. Verification (acceptance criteria)

**AC1: only 80/443 reachable.** From a machine outside AWS:

```bash
nmap -Pn -p 22,80,443,5432 <elastic-ip>
```

Expect 22 and 5432 closed/filtered. 80 and 443 show as closed until MBD-9 runs nginx, so confirm the
reachability side through the security group rules (the rule list above), or re-run the scan after MBD-9.

**AC2: SSM works, no port 22 rule.**

```bash
aws ssm start-session --target <instance-id> --region us-east-1
aws ec2 describe-security-groups --group-ids sg-04cacc0cf6c329e48 --region us-east-1 --query "SecurityGroups[0].IpPermissions[].[FromPort,ToPort]"
```

The second command must list only 80 and 443.

**AC3: IAM permissions.**

```bash
aws iam simulate-principal-policy --policy-source-arn arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-host-role --action-names s3:PutObject s3:GetObject s3:DeleteObject --resource-arns arn:aws:s3:::musicboxd-images/test.jpg
# expect allowed

aws iam simulate-principal-policy --policy-source-arn arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-host-role --action-names s3:DeleteObject --resource-arns arn:aws:s3:::musicboxd-backups/test.sql
# expect implicitDeny

aws iam simulate-principal-policy --policy-source-arn arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-host-role --action-names s3:GetObject --resource-arns arn:aws:s3:::some-other-bucket/x
# expect implicitDeny

aws iam simulate-principal-policy --policy-source-arn arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-host-role --action-names ses:SendEmail iam:ListUsers --resource-arns "*"
# expect implicitDeny
```

**AC4: IMDSv2 enforced.** On the instance:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://169.254.169.254/latest/meta-data/
# expect 401

TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id
# expect the instance ID
```

**AC5:** this file lists every ID and setting and is committed.

### Verification results

| AC  | Result | Date |
| --- | ------ | ---- |
| 1   | `TODO` |      |
| 2   | `TODO` |      |
| 3   | `TODO` |      |
| 4   | `TODO` |      |
| 5   | `TODO` |      |

## MBD-9: Compose stack and TLS

Runs the `api` / `postgres` / `nginx` stack from `deploy/docker-compose.prod.yml` on this host, with nginx
terminating TLS (Let's Encrypt via certbot, HTTP-01). Fields marked `TODO` are filled in while deploying.
Swapping the domain later, and any SPA/CORS origin change, is not part of this ticket.

| Item                         | Value                                                  |
| ---------------------------- | ------------------------------------------------------ |
| Domain (`DOMAIN`)            | `musicboxd.com.br` (apex, A record to the Elastic IP, no AAAA; `www` is not served or covered by the cert) |
| Stack directory on the host  | `/opt/musicboxd` (copy of the repo's `deploy/`)         |
| Env files (root, mode 600)   | `/etc/musicboxd/stack.env`, `/etc/musicboxd/postgres.env`, `/etc/musicboxd/api.env` |
| Images                       | `ghcr.io/joaovitorzanardo/musicboxd-api`, `musicboxd-web` (`IMAGE_TAG`, default `latest`) |
| Compose project / volumes    | project `musicboxd`; volumes `postgres_data`, `letsencrypt`, `certbot_www` |
| Certificate renewal          | `musicboxd-certbot-renew.timer`, twice daily, reloads nginx only after a renewal |
| GHCR access                  | public packages (no `docker login` needed) |

All commands below run in an SSM session, from `/opt/musicboxd`. Every compose command is written out in full
(no aliases, which are lost on each new SSM session). Start each session with:

```bash
cd /opt/musicboxd
```

### Prerequisites

1. This work is merged to `main` and CI ("Publish images") has pushed `musicboxd-api` and `musicboxd-web` to GHCR.
   Check the Actions run and the packages page.
2. DNS: an **A record** for the chosen hostname pointing at the Elastic IP (recorded in the inventory above).
   - **No AAAA record.** Let's Encrypt prefers IPv6 for HTTP-01 and this host does not serve it.
   - If the DNS provider proxies traffic (for example Cloudflare), set the record to "DNS only".
   - If the domain has CAA records, they must allow `letsencrypt.org`.
   - `nslookup <domain>` must return the Elastic IP before you issue a certificate.
3. A GHCR decision: make both packages public, or create a read-only `read:packages` token for the host.

### 1. Install the repo files and env files

```bash
sudo dnf install -y git
sudo git clone https://github.com/joaovitorzanardo/musicboxd.git /opt/musicboxd-src
sudo mkdir -p /opt/musicboxd && sudo cp -r /opt/musicboxd-src/deploy/. /opt/musicboxd/
sudo install -d -m 700 /etc/musicboxd
```

If the repo is private, clone with a read-only deploy key or token and do not leave it on disk.

Create the three env files from `deploy/prod.env.example` (drop the inline `#` comments), then lock them down:

```bash
sudoedit /etc/musicboxd/stack.env       # DOMAIN, GHCR_OWNER (lowercase), IMAGE_TAG, CERTBOT_EMAIL
sudoedit /etc/musicboxd/postgres.env    # POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
sudoedit /etc/musicboxd/api.env         # MUSICBOXD_JWT_SECRET
sudo chmod 600 /etc/musicboxd/*.env
```

Generate the Postgres password with `openssl rand -base64 24` and the JWT signing secret with
`openssl rand -base64 32` (the api refuses to start without `MUSICBOXD_JWT_SECRET`). Secrets stay on the host and never go in the repo.

### 2. Log in to GHCR (only if the packages are private)

```bash
echo "<read:packages token>" | sudo docker login ghcr.io -u <github-user> --password-stdin
# expect: Login Succeeded
```

The login persists in `/root/.docker/config.json`; MBD-10 (CD) pulls rely on it.

### 3. Issue the certificate (before the stack is started)

nginx cannot load its 443 block without the certificate files, and it would crash-loop while holding port 80.
So **do not run `docker compose up` before this step succeeds.** If you did, run `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml down` first.

```bash
sudo ss -ltnp | grep ':80 ' || echo "port 80 free"      # must be free
sudo ./certbot/init-cert.sh --staging                    # expect: Certificate issued (STAGING ...)
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml run --rm -T --no-deps --entrypoint sh certbot -c "rm -rf /etc/letsencrypt/*"
sudo ./certbot/init-cert.sh                              # expect: Certificate issued.
```

The staging run proves DNS and port 80 work without spending the production rate limit (5 failures per hour,
5 duplicate certificates per week). Re-running `init-cert.sh` without `--staging` while a staging certificate
is present exits with a warning and the reset steps.

### 4. Start the stack

```bash
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml pull
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml ps
```

Expect `api` healthy, `postgres` and `nginx` running, nginx publishing `0.0.0.0:80` and `0.0.0.0:443`.
Containers restart on their own (`restart: unless-stopped`), and container logs rotate at 10 MB x 3 files.

### 5. Install the renewal timer

```bash
sudo cp /opt/musicboxd/systemd/musicboxd-certbot-renew.* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now musicboxd-certbot-renew.timer
systemctl list-timers musicboxd-certbot-renew.timer      # NEXT must be in the future
```

`renew-cert.sh` runs `certbot renew` over the webroot, and only if a certificate actually renewed it reloads nginx.
If the reload fails, the next run retries it.

### Verification (MBD-9 acceptance criterion)

Goal: the domain serves the SPA over HTTPS from this host, with a valid certificate that renews automatically and
reloads nginx.

**V1: HTTPS and a valid certificate** (from a machine outside AWS):

```bash
curl -sS -o /dev/null -w "%{http_code} %{ssl_verify_result}\n" https://<domain>/    # expect: 200 0
curl -sS https://<domain>/ | grep -i '<div id="root"'                               # SPA shell present
curl -sS -o /dev/null -w "%{http_code}\n" https://<domain>/some/deep/link           # expect: 200
curl -sSI http://<domain>/ | grep -i '^location: https://'                          # HTTP redirects
curl -sS -o /dev/null -w "%{http_code}\n" http://<domain>/.well-known/acme-challenge/x   # expect: 404, not a redirect
echo | openssl s_client -connect <domain>:443 -servername <domain> 2>/dev/null | openssl x509 -noout -issuer -dates
# expect: Let's Encrypt issuer, notAfter about 90 days out
```

**V2: API through the proxy:**

```bash
curl -sS -o /dev/null -w "%{http_code}\n" https://<domain>/api/v1/api-docs    # expect: 200
```

**V3: renewal and reload** (on the host; no certificate swap, `init-cert.sh` needs port 80 and nginx holds it):

```bash
sudo ./certbot/renew-cert.sh --dry-run          # webroot renewal through the running nginx; expect: no failure, "No certificate due"
# simulate "a certificate just renewed" by touching the marker, then run the real unit
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml run --rm -T --no-deps --entrypoint sh certbot -c 'touch /etc/letsencrypt/.renewed'
sudo systemctl start musicboxd-certbot-renew.service
systemctl status musicboxd-certbot-renew.service --no-pager                    # expect: status=0/SUCCESS
sudo journalctl -u musicboxd-certbot-renew.service --no-pager | tail           # expect: "Certificate renewed; nginx reloaded."
sudo systemctl start musicboxd-certbot-renew.service
sudo journalctl -u musicboxd-certbot-renew.service --no-pager | tail -3        # expect: "No certificate due for renewal."
```

**V4: only 80/443 public, Postgres not exposed** (from outside AWS; also closes AC1 of MBD-8 above):

```bash
nmap -Pn -p 22,80,443,5432 <elastic-ip>        # expect: 80 and 443 open; 22 and 5432 filtered
```

**V5: reboot survival:** `sudo reboot`, reconnect, wait 1-2 minutes (nginx can restart-loop briefly until `api`
resolves), then `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml ps`, `systemctl list-timers musicboxd-certbot-renew.timer`, and repeat the first V1 curl.

| Check | Result | Date |
| ----- | ------ | ---- |
| V1    | `TODO` |      |
| V2    | `TODO` |      |
| V3    | `TODO` |      |
| V4    | `TODO` |      |
| V5    | `TODO` |      |

### Operations notes

- **Deploying new images (until MBD-10):** set `IMAGE_TAG` in `stack.env` (a `sha-<long>` tag from CI, or `latest`),
  then `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml pull && sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d`.
- **Lost or empty `letsencrypt` volume:** nginx will crash-loop with no certificate. Run `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml stop nginx`, then
  `sudo ./certbot/init-cert.sh`, then `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d`.
- **Not in this ticket:** certificate expiry alarm (AD-11, deferred), real-domain swap, Postgres backups (MBD-11, see runbook-mbd-11-postgres-backups.md),
  CD (MBD-10), rate limiting (MBD-13).

## Cost (AD-10, approximate, verify against AWS pricing)

| Item                     | $/month    |
| ------------------------ | ---------- |
| t4g.small                | ~12        |
| gp3 20 GB                | ~1.6       |
| Public IPv4 / Elastic IP | ~3.65      |
| **Total**                | **~17-18** |
