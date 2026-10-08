# Runbook MBD-21 — The first STAFF account, and checking admin protection

STAFF is a column on `accounts.accounts` (`USER` by default). There is no API that promotes anyone. The api
promotes the email in `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL` on **every start**, through the repeatable migration
`R__promote_bootstrap_staff.sql`. The role reaches the access token at the next login or refresh (≤ 15 minutes).

## First production deploy (one time, a person does this)

1. After MBD-21 is deployed, register the Staff account through the site (or `POST /api/v1/auth/register`) and
   click the verification link.
2. On the EC2 host (SSM session), add the variable and restart the api:

   ```bash
   sudo sh -c 'printf "MUSICBOXD_BOOTSTRAP_STAFF_EMAIL=%s\n" "staff@your-domain" >> /etc/musicboxd/api.env'
   sudo docker compose -f /opt/musicboxd/docker-compose.prod.yml --env-file /etc/musicboxd/stack.env up -d api
   ```

   A malformed value (a quote, a space, no `@`) stops the api at startup with a message naming the variable;
   `deploy.sh` then rolls back. Fix the value and restart.
3. Confirm:

   ```bash
   sudo docker compose -f /opt/musicboxd/docker-compose.prod.yml --env-file /etc/musicboxd/stack.env exec postgres \
     psql -U musicboxd -d musicboxd -c "SELECT email, role FROM accounts.accounts WHERE role = 'STAFF';"
   ```

4. Log out and in again (or wait for the next refresh): the new access token carries `"role":"STAFF"`.

## Demoting someone

Removing the variable does not demote. Do it by hand; it applies at their next access token (≤ 15 min):

```sql
UPDATE accounts.accounts SET role = 'USER' WHERE lower(email) = lower('someone@example.com');
```

Also remove or change `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL`, or the next restart promotes them again.

## Checking admin protection (before merge and after each deploy)

The story's risk note asks for a **sample** of admin routes, not one. With `USER` and `STAFF` access tokens
(`$U`, `$S`) from `POST /api/v1/auth/login`:

```bash
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
