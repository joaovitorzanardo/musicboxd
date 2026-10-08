# Runbook MBD-21 — The first STAFF account, and checking admin protection

STAFF is a column on `accounts.accounts` (`USER` by default). There is no API that promotes anyone. On
**every start** the api promotes the *verified* account whose email is `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL`, through
the Flyway callback `afterMigrate__promote_bootstrap_staff.sql` (it records nothing in `flyway_schema_history`).
The role reaches the access token at the next login or refresh (≤ 15 minutes).

All commands run on the EC2 host (SSM session), in `/opt/musicboxd`.

## First production deploy (one time, a person does this)

1. After MBD-21 is deployed, register the Staff account with **your own password** through the site (or
   `POST /api/v1/auth/register`). The response must be **HTTP 201**. A **409** means someone already holds that
   email: stop and investigate; do **not** set the variable.
2. Click the verification link in the email. Only verified accounts are promoted.
3. Set the variable and restart the api. `prod.env.example` ships an empty `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL=`
   line, so edit the file instead of appending:

   ```bash
   sudoedit /etc/musicboxd/api.env     # set MUSICBOXD_BOOTSTRAP_STAFF_EMAIL=staff@your-domain (one line only)
   cd /opt/musicboxd
   sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d api
   ```

   A malformed value (a quote, a space, no `@`) makes the api exit at startup with a message naming the
   variable. **Nothing rolls back**: this is a plain `up -d api`, so the api crash-loops until `api.env` is fixed.
   Check, fix, restart:

   ```bash
   sudo docker compose --env-file /etc/musicboxd/stack.env -f /opt/musicboxd/docker-compose.prod.yml logs api --tail 50 | grep MUSICBOXD_BOOTSTRAP_STAFF_EMAIL
   sudoedit /etc/musicboxd/api.env
   sudo docker compose --env-file /etc/musicboxd/stack.env -f /opt/musicboxd/docker-compose.prod.yml up -d api
   ```
4. Confirm in the database:

   ```bash
   sudo docker compose --env-file /etc/musicboxd/stack.env -f /opt/musicboxd/docker-compose.prod.yml exec postgres \
     psql -U musicboxd -d musicboxd -c "SELECT email, role FROM accounts.accounts WHERE role = 'STAFF';"
   ```

5. Log in with your own password (`POST /api/v1/auth/login`) and decode the access token's payload: it must
   carry `"role":"STAFF"`.
6. **Remove the variable.** `sudoedit /etc/musicboxd/api.env`, delete the `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL`
   value (or the line), then `up -d api` again as in step 3. The role stays; removing the variable never
   demotes. Leaving it set would promote whoever later holds that email.

## Demoting someone

The variable should already be gone (step 6). If it is still set, remove it first, or the next restart promotes
them again. Then demote by hand; it applies at their next access token (≤ 15 min):

```sql
UPDATE accounts.accounts SET role = 'USER' WHERE lower(email) = lower('someone@example.com');
```

## Rollback

Rolling back to a pre-MBD-21 image is safe:

- V4's `role` column is ignored by older code, and Flyway treats V4 as a future migration.
- The promotion is a callback and leaves no `flyway_schema_history` row, so the older image still validates.
- A `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL` line in `api.env` is ignored by older images.

## Checking admin protection (before merge and after each deploy)

The story's risk note asks for a **sample** of admin routes, not one. Get `USER` and `STAFF` access tokens
(`$U`, `$S`) from `POST /api/v1/auth/login` (the `accessToken` field) for a normal account and the Staff account:

```bash
D=https://<domain>
U=<user access token>
S=<staff access token>
for m in GET POST PUT PATCH DELETE; do
  for p in /api/v1/admin /api/v1/admin/catalog/albums /api/v1/admin/catalog/albums/1 /api/v1/admin/reviews/1; do
    printf '%-6s %-34s user=%s staff=%s anon=%s\n' $m $p \
      "$(curl -s -o /dev/null -w '%{http_code}' -X $m -H "Authorization: Bearer $U" $D$p)" \
      "$(curl -s -o /dev/null -w '%{http_code}' -X $m -H "Authorization: Bearer $S" $D$p)" \
      "$(curl -s -o /dev/null -w '%{http_code}' -X $m $D$p)"
  done
done
```

Expected: every `user=403`, every `anon=401`, and `staff` never 401/403 (404 until MBD-28 adds real admin routes).
