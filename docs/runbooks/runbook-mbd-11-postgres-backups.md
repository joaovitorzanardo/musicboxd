# Runbook: MBD-11 Postgres backups to S3 and the restore drill

Daily `pg_dump` of the production database to S3, retention by an S3 lifecycle rule, and a restore drill
that proves a dump restores into a fresh Postgres with identical data. Human-run (`hitl`, `risk: high`).

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

## 1. Create the bucket (admin credentials, from your machine, at the repo root)

```bash
aws s3api create-bucket --bucket musicboxd-backups --region us-east-1
aws s3api put-public-access-block --bucket musicboxd-backups \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-versioning --bucket musicboxd-backups --versioning-configuration Status=Enabled
aws s3api put-bucket-lifecycle-configuration --bucket musicboxd-backups \
  --lifecycle-configuration file://deploy/aws/backup-bucket-lifecycle.json
```

If `create-bucket` says `BucketAlreadyExists`, use `musicboxd-backups-<suffix>` everywhere below, update the
`musicboxd-s3` inline policy's two backup ARNs (runbook-aws-host.md section 2), and record the name in its inventory.

Read it all back:

```bash
aws s3api get-public-access-block --bucket musicboxd-backups      # all four true
aws s3api get-bucket-versioning --bucket musicboxd-backups        # "Status": "Enabled"
aws s3api get-bucket-encryption --bucket musicboxd-backups        # SSEAlgorithm AES256
aws s3api get-bucket-lifecycle-configuration --bucket musicboxd-backups   # the two rules
```

## 2. Prepare the host (SSM session)

```bash
aws ssm start-session --target <instance-id> --region us-east-1
cd /opt/musicboxd
aws --version                                    # AWS CLI v2 ships with Amazon Linux 2023
aws sts get-caller-identity                      # Arn must be .../assumed-role/musicboxd-host-role/...
ls -l backup/                                    # both scripts present and executable (-rwxr-xr-x), i.e. CD ran after the merge
sudoedit /etc/musicboxd/stack.env                # add: BACKUP_BUCKET=musicboxd-backups  and  AWS_REGION=us-east-1
```

## 3. Install the timer

```bash
sudo cp /opt/musicboxd/systemd/musicboxd-db-backup.* /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/musicboxd-db-backup.service   # no output = OK
sudo systemctl daemon-reload
sudo systemctl enable --now musicboxd-db-backup.timer
systemctl list-timers musicboxd-db-backup.timer   # NEXT must be in the future
```

## 4. First backup by hand

```bash
sudo systemctl start musicboxd-db-backup.service
systemctl status musicboxd-db-backup.service --no-pager                   # status=0/SUCCESS
sudo journalctl -u musicboxd-db-backup.service --no-pager | tail -3        # "Backup uploaded: s3://.../postgres/musicboxd-...dump (N bytes)"
aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1
```

## 5. Restore drill (acceptance criterion + high-risk check)

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

**Person checks (high-risk gate).** Look at the restored copy yourself and compare it with the live database:

```bash
Q='SELECT count(*), count(DISTINCT token), min(token), count(*) FILTER (WHERE note IS NULL) FROM ops_restore_drill.canary'
sudo docker exec musicboxd-restore-check sh -c "psql -X -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$Q\""
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c "psql -X -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$Q\""
# both: 1000 | 1 | <your token> | 10
```

Clean up:

```bash
sudo docker rm -f musicboxd-restore-check
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec -T postgres \
  sh -c 'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DROP SCHEMA ops_restore_drill CASCADE"'
```

(The drill dump keeps the canary schema until it expires. That is harmless.)

## 6. Scheduled run (the next day)

```bash
systemctl list-timers musicboxd-db-backup.timer                       # LAST is today ~04:30-04:45 UTC
sudo journalctl -u musicboxd-db-backup.service --since today --no-pager | tail -2
aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1         # a key stamped in today's 04:30-04:45 window
```

## 7. The host cannot delete backups

```bash
aws s3 rm s3://musicboxd-backups/postgres/<a key from above> --region us-east-1   # expect: AccessDenied
```

## Verification results

| Check | Expected | Result | Date |
| ----- | -------- | ------ | ---- |
| V1 bucket settings (step 1 read-back) | public access blocked, versioning on, AES256, two lifecycle rules | `TODO` | |
| V2 manual backup (step 4) | object in `postgres/`, unit SUCCESS | `TODO` | |
| V3 restore drill (step 5) | `MATCH`, exit 0 | `TODO` | |
| V4 person check (step 5) | both queries `1000 \| 1 \| <token> \| 10`; checked by: `TODO name` | `TODO` | |
| V5 scheduled run (step 6) | object from the timer window, no manual start | `TODO` | |
| V6 no delete (step 7) | AccessDenied | `TODO` | |

## Day-to-day checks

- Is the backup running? `systemctl list-timers musicboxd-db-backup.timer` and `systemctl --failed`.
  A failed run leaves the unit in `failed` state until the next success. Newest object:
  `aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1 | tail -1`.
- **No alarm fires on a failed backup yet.** Follow-up: alert when the newest object is older than 26h.
- Re-run the drill after any Postgres major-version change (`postgres:18` → 19). `RESTORE_IMAGE` must match
  the service image, and `restore-check.sh`'s default must be bumped with it.
- Once real users write data, a live comparison shows writes made after the dump as `MISMATCH`. For a drill
  on a busy database, run `sudo COMPARE=0 KEEP=1 ./backup/restore-check.sh` and inspect by hand.

## Disaster recovery: restore into production

Not exercised against production. It uses the same `pg_restore` invocation the drill exercises.

```bash
cd /opt/musicboxd
C=(sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml)
sudo systemctl start musicboxd-db-backup.service || true     # snapshot the current state first, if it is reachable
aws s3 ls s3://musicboxd-backups/postgres/ --region us-east-1 # pick KEY
aws s3 cp s3://musicboxd-backups/postgres/<KEY> /var/tmp/restore.dump --region us-east-1
"${C[@]}" stop api                                            # nginx keeps serving the SPA; API returns 502
"${C[@]}" exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
"${C[@]}" exec -T postgres sh -c 'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < /var/tmp/restore.dump
"${C[@]}" up -d api
sudo rm -f /var/tmp/restore.dump
```

To restore an object the lifecycle has already expired as a "current" version (within its 7 noncurrent
days), use an admin session: `aws s3api list-object-versions --bucket musicboxd-backups --prefix postgres/`,
then `aws s3api get-object --version-id ...`.

## Cost

Some dumps of a small database (~30 current + ~7 noncurrent) cost well under $0.10/month in S3 Standard.
