# Runbook: MBD-11 Postgres backups to S3 and the restore drill

Daily `pg_dump` of the production database to S3, retention by an S3 lifecycle rule, and a restore drill
that proves a dump restores into a fresh Postgres with identical data. Human-run (`hitl`, `risk: high`).

Everything on the AWS side is done in the **AWS console** (signed in as root or an admin user, region
**US East (N. Virginia) us-east-1** in the top-right selector). The host steps need a shell, and you open
that shell from the console too (section 4). Nothing here needs the AWS CLI on your own machine.

| Item | Value |
| ---- | ----- |
| Bucket | `musicboxd-backups` (or the suffixed name recorded in `runbook-aws-host.md`), region `us-east-1` |
| Key layout | `postgres/musicboxd-YYYYMMDDTHHMMSSZ.dump` (UTC, `pg_dump -Fc`) |
| Schedule | `musicboxd-db-backup.timer`, daily 04:30 UTC + up to 15 min random delay, catches up after downtime |
| Retention | lifecycle: current 30 days, noncurrent 7 days, incomplete uploads 1 day (`deploy/aws/backup-bucket-lifecycle.json`) |
| Access | host role `musicboxd-host-role`: Put/Get objects + List bucket, **no delete** (MBD-8) |
| Encryption | SSE-S3 (bucket default); public access fully blocked |
| Scripts on host | `/opt/musicboxd/backup/backup-db.sh`, `/opt/musicboxd/backup/restore-check.sh` (copied by CD) |

Dumps will contain user data (emails, password hashes) once those features ship. Nobody but the host
role and the account admin may read this bucket.

**Before you start:** this branch is merged to `main` and the CD run after the merge succeeded (GitHub → Actions →
"Publish images" → green). CD is what copies `deploy/backup/` and `deploy/systemd/` onto the host.

## 1. Create the bucket (console: S3)

1. Console search bar → **S3** → **Create bucket**.
2. **General configuration**
   - AWS Region: **US East (N. Virginia) us-east-1**
   - Bucket type: **General purpose**
   - Bucket name: `musicboxd-backups`
3. **Object Ownership**: **ACLs disabled (recommended)**.
4. **Block Public Access settings for this bucket**: keep **Block *all* public access** checked (all four boxes).
5. **Bucket Versioning**: **Enable**.
6. **Default encryption**: **Server-side encryption with Amazon S3 managed keys (SSE-S3)**. Bucket Key: leave as is.
7. **Advanced settings → Object Lock**: **Disable**.
8. **Create bucket**.

If it fails with *"Bucket with the same name already exists"*, the name is taken globally. Use
`musicboxd-backups-<suffix>` (e.g. your initials or a date) and use that name **everywhere** below. That
includes the policy in section 3 and `stack.env` in section 4. Record it in the inventory of `runbook-aws-host.md`.

## 2. Add the retention rules (console: S3 → bucket → Management)

Open the bucket → **Management** tab → **Lifecycle rules** → **Create lifecycle rule**. Create **two** rules.
The console does not allow "expire current versions" and "delete expired object delete markers" in the same rule.

**Rule 1**

| Field | Value |
| ----- | ----- |
| Lifecycle rule name | `expire-postgres-dumps` |
| Choose a rule scope | **Limit the scope of this rule using one or more filters** |
| Filter type → Prefix | `postgres/` |
| Lifecycle rule actions | check **Expire current versions of objects**, **Permanently delete noncurrent versions of objects**, **Delete expired object delete markers or incomplete multipart uploads** |
| Expire current versions → Days after object creation | `30` |
| Permanently delete noncurrent versions → Days after objects become noncurrent | `7` (leave "Number of newer versions to retain" empty) |
| Delete expired object delete markers or incomplete multipart uploads | check only **Delete incomplete multipart uploads**, Number of days `1` |

**Create rule**.

**Rule 2**

| Field | Value |
| ----- | ----- |
| Lifecycle rule name | `remove-expired-delete-markers` |
| Choose a rule scope | **Limit the scope of this rule using one or more filters** |
| Filter type → Prefix | `postgres/` |
| Lifecycle rule actions | check only **Delete expired object delete markers or incomplete multipart uploads** |
| Below it | check only **Delete expired object delete markers** |

**Create rule**.

These are the same rules as `deploy/aws/backup-bucket-lifecycle.json`. A dump stays current for 30 days and
noncurrent for 7 more, then it is gone. Nothing lives longer than about 37 days.

**Check the bucket (V1).** On the bucket page:

- **Permissions** tab → Block public access: **On**
- **Properties** tab → Bucket Versioning: **Enabled**; Default encryption: **SSE-S3**
- **Management** tab → both rules listed, Status **Enabled**

## 3. Check the host role can use the bucket (console: IAM)

MBD-8 should already have granted this. Confirm it:

1. Console search bar → **IAM** → **Roles** → search `musicboxd-host-role` → open it.
2. **Permissions** tab → in **Permissions policies**, expand the inline policy **`musicboxd-s3`** (the `+` next to its name).
3. The JSON must contain these two statements, with the **exact** bucket name from section 1:

```json
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
```

If they are missing, or the bucket name differs, open the policy name → **Edit** → **JSON**. Add or fix the two
statements inside `"Statement": [ ... ]` (separate them from the others with commas) → **Next** → **Save changes**.

**Do not add `s3:DeleteObject`** for the backup bucket. The host must not be able to delete backups (section 9 checks this).

## 4. Prepare the host (console: EC2 → Connect)

Open a shell on the host:

1. Console search bar → **EC2** → **Instances** → check `musicboxd-host`.
2. **Connect** (top right) → **Session Manager** tab → **Connect**.
3. A terminal opens in a new browser tab as `ssm-user`. Paste with **Ctrl+Shift+V** or right-click → Paste.
   Multi-line blocks below can be pasted in one go.

Every host section below (5–9) runs in this terminal. If it disconnects, reconnect the same way.

```bash
cd /opt/musicboxd
aws --version                                          # AWS CLI v2 ships with Amazon Linux 2023
aws sts get-caller-identity --region us-east-1         # Arn must be .../assumed-role/musicboxd-host-role/...
ls -l backup/                                          # backup-db.sh, restore-check.sh, stack-env.sh; first two -rwxr-xr-x
```

If `backup/` does not exist, CD has not run since the merge. Re-run the "Publish images" workflow in GitHub Actions, then check again.

Add the two settings to `stack.env`. Change the bucket name first if you used a suffixed one:

```bash
sudo grep -q '^BACKUP_BUCKET=' /etc/musicboxd/stack.env \
  || printf '\nBACKUP_BUCKET=musicboxd-backups\nAWS_REGION=us-east-1\n' | sudo tee -a /etc/musicboxd/stack.env >/dev/null
sudo grep -E '^(BACKUP_BUCKET|AWS_REGION)=' /etc/musicboxd/stack.env
# expect exactly:
# BACKUP_BUCKET=musicboxd-backups
# AWS_REGION=us-east-1
```

## 5. Install the timer (host terminal)

```bash
sudo cp /opt/musicboxd/systemd/musicboxd-db-backup.* /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/musicboxd-db-backup.service 2>&1 | grep musicboxd || echo "unit OK"
# expect: unit OK. Warnings about other units (e.g. acpid.socket and /var/run) come from OS units and are harmless.
sudo systemctl daemon-reload
sudo systemctl enable --now musicboxd-db-backup.timer
systemctl list-timers musicboxd-db-backup.timer   # NEXT must be in the future
```

## 6. First backup by hand (host terminal + S3 console)

```bash
sudo systemctl start musicboxd-db-backup.service
systemctl status musicboxd-db-backup.service --no-pager                   # status=0/SUCCESS
sudo journalctl -u musicboxd-db-backup.service --no-pager | tail -3        # "Backup uploaded: s3://.../postgres/musicboxd-...dump (N bytes)"
```

**Check in the console (V2).** S3 → `musicboxd-backups` → **Objects** → folder `postgres/` must contain
`musicboxd-<date>T<time>Z.dump` with a non-zero size and a recent **Last modified**.

## 7. Restore drill (acceptance criterion + high-risk check, host terminal)

The production database has no application tables yet, and an empty restore proves nothing
(`restore-check.sh` refuses it with exit 2). So first seed a canary with a random token, then back up and
restore. Run everything in one session, without deploys in between, so live and restored data are the same.

```bash
cd /opt/musicboxd
TOKEN=$(openssl rand -hex 8); echo "canary token: $TOKEN"     # write it down
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<SQL
CREATE SCHEMA ops_restore_drill;
CREATE TABLE ops_restore_drill.canary (id int PRIMARY KEY, token text NOT NULL, note text, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO ops_restore_drill.canary (id, token, note)
SELECT g, '$TOKEN', CASE WHEN g % 100 = 0 THEN NULL ELSE 'row ' || g || E'\nwith a newline and ünïcode' END
FROM generate_series(1, 1000) g;
SQL

sudo systemctl start musicboxd-db-backup.service
sudo journalctl -u musicboxd-db-backup.service --no-pager | tail -1     # note the key

sudo KEEP=1 ./backup/restore-check.sh                 # expect: "ops_restore_drill.canary  1000 rows" and "MATCH: ..."; exit 0
echo "exit=$?"
```

**Person checks (high-risk gate, V4).** Look at the restored copy yourself and compare it with the live database:

```bash
Q='SELECT count(*), count(DISTINCT token), min(token), count(*) FILTER (WHERE note IS NULL) FROM ops_restore_drill.canary'
sudo docker exec musicboxd-restore-check sh -c "psql -X -At -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$Q\""
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c "psql -X -At -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$Q\""
# both lines: 1000|1|<your token>|10
```

Clean up:

```bash
sudo docker rm -fv musicboxd-restore-check     # -v: also removes the anonymous volume holding the restored data
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DROP SCHEMA ops_restore_drill CASCADE"'
```

(The drill dump keeps the canary schema until it expires. That is harmless.)

## 8. Scheduled run (the next day, host terminal + S3 console)

```bash
systemctl list-timers musicboxd-db-backup.timer                       # LAST is today ~04:30-04:45 UTC
sudo journalctl -u musicboxd-db-backup.service --since today --no-pager | tail -2
```

**Check in the console (V5).** S3 → `musicboxd-backups` → `postgres/`. There must be a new dump whose name is
stamped between 04:30 and 04:45 UTC today, which you did not start by hand. The console shows **Last modified**
in your local time zone (UTC−3 in Brazil: 01:30–01:45).

## 9. The host cannot delete backups (host terminal)

Run this on the **host**, not in the S3 console. As an admin you *can* delete in the console, and that proves nothing.

```bash
aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1 | tail -1          # copy a key
aws s3 rm s3://musicboxd-backups/postgres/<that key> --region us-east-1          # expect: AccessDenied
```

## Verification results

| Check | Expected | Result | Date |
| ----- | -------- | ------ | ---- |
| V1 bucket settings (section 2 check) | public access blocked, versioning on, SSE-S3, two lifecycle rules enabled | `TODO` | |
| V2 manual backup (section 6) | object in `postgres/`, unit SUCCESS | `TODO` | |
| V3 restore drill (section 7) | `MATCH`, exit 0 | `TODO` | |
| V4 person check (section 7) | both queries `1000\|1\|<token>\|10`; checked by: `TODO name` | `TODO` | |
| V5 scheduled run (section 8) | object from the timer window, no manual start | `TODO` | |
| V6 no delete (section 9) | AccessDenied | `TODO` | |

## Day-to-day checks

- **Every Monday**, check that the backup is running. A failed run leaves the unit in `failed` state until
  the next success, and a hung run is killed after 1 hour (`TimeoutStartSec`) and shows as failed.
  - Console: S3 → `musicboxd-backups` → `postgres/` → sort by **Last modified**. The newest dump is less than ~24h old.
  - Host terminal (section 4):

    ```bash
    systemctl list-timers musicboxd-db-backup.timer     # LAST within the past ~24h
    systemctl --failed                                  # musicboxd-db-backup.service must not be listed
    ```

- **Risk: no alarm fires on a failed backup yet, and the lifecycle does not wait for newer dumps.** If the timer
  silently stops (a bad `stack.env` edit, the unit not re-enabled after a host rebuild), the last good dump
  still expires after 30 days + 7 noncurrent, and then **no backup exists at all**. The weekly check above is
  the only guard until the freshness alarm ticket (alert when the newest object is older than 26h) ships.
- Re-run the drill after any Postgres major-version change (`postgres:18` → 19). `RESTORE_IMAGE` must match
  the service image, and `restore-check.sh`'s default must be bumped with it.
- Once real users write data, a live comparison shows writes made after the dump as `MISMATCH`. For a drill
  on a busy database, run `sudo COMPARE=0 KEEP=1 ./backup/restore-check.sh` and inspect by hand.

## Disaster recovery: restore into production

Not exercised against production. It uses the same `pg_restore` invocation the drill exercises.
Pick the dump in the console (S3 → `musicboxd-backups` → `postgres/`), then run this in the host terminal:

```bash
cd /opt/musicboxd
C=(sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml)
sudo systemctl start musicboxd-db-backup.service || true     # snapshot the current state first, if it is reachable
aws s3 cp s3://musicboxd-backups/postgres/<KEY> /var/tmp/restore.dump --region us-east-1
"${C[@]}" stop api                                            # nginx keeps serving the SPA; API returns 502
"${C[@]}" exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
"${C[@]}" exec -T postgres sh -c 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < /var/tmp/restore.dump
"${C[@]}" up -d api
sudo rm -f /var/tmp/restore.dump
```

**The dump you need has expired** (it disappears from the list for up to 7 days before it is really deleted).
The host role cannot read old versions, so bring it back as the current version in the console first:

1. S3 → `musicboxd-backups` → `postgres/` → turn on **Show versions**.
2. Find the row for that key with type **Delete marker**. Select **only** that row → **Delete** → type
   `permanently delete` → **Delete objects**.
3. Turn **Show versions** off. The dump is listed again, and the host can download it with the commands above.

## Cost

Some dumps of a small database (~30 current + ~7 noncurrent) cost well under $0.10/month in S3 Standard.
