# Runbook MBD-17 — Accounts: JWT secret and login smoke test

## Before merging MBD-17 (one time, on the EC2 host)

The api now refuses to start without `MUSICBOXD_JWT_SECRET`, and the prod compose file lists `/etc/musicboxd/api.env`
as an env file. If that file is missing, `deploy.sh` copies the new compose file and then compose refuses to load the
project ("env file ... not found") before any health check runs. The automatic rollback uses the same compose file, so it
fails too ("Rollback to ... also failed"); the old containers keep running only because compose never touched them.
`deploy/backup/backup-db.sh` and `restore-check.sh` load the same project, so nightly backups and the restore drill also
fail until `api.env` exists. Create it first (SSM session on the host):

```bash
sudo sh -c 'umask 077; printf "MUSICBOXD_JWT_SECRET=%s\n" "$(openssl rand -base64 32)" > /etc/musicboxd/api.env'
sudo chown root:root /etc/musicboxd/api.env
sudo ls -l /etc/musicboxd/api.env   # -rw------- root root
```

## After the deploy: smoke test (replace DOMAIN)

```bash
D=https://DOMAIN
curl -s -X POST $D/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"smoke+1@example.com","password":"correct-horse","username":"smoke_1"}'      # 201
curl -s -o /dev/null -w '%{http_code}\n' -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"smoke+1@example.com","password":"wrong-password"}'                          # 401
TOKEN=$(curl -s -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"smoke+1@example.com","password":"correct-horse"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s $D/api/v1/accounts/me -H "Authorization: Bearer $TOKEN"                            # 200, username smoke_1
curl -s -o /dev/null -w '%{http_code}\n' $D/api/v1/accounts/me                              # 401
```

The smoke account stays in the database (account deletion is deferred, AD-12); use a fresh email each run.

## Rotating the secret

Replace the value in `/etc/musicboxd/api.env` and run `docker compose ... up -d api`. Every issued access
token stops working at once; people log in again (no refresh tokens until MBD-19).
