# Runbook: MBD-10 end-to-end verification of CD (plan Task 4)

Goal (acceptance criterion): **a push to `main` results in the EC2 host running the new image tags within the
workflow's run, with no manual step.**

This runs against the live host, so you run it. Every command is on a single line so it can be pasted as is.
Commands marked **[local]** run on your machine (AWS CLI and `gh` configured). Commands marked **[host]** run in
an SSM session:

```bash
aws ssm start-session --target <INSTANCE_ID> --region us-east-1
```

Values: repo `joaovitorzanardo/musicboxd`, region `us-east-1`, instance `<INSTANCE_ID>`,
domain `musicboxd.com.br`, stack at `/opt/musicboxd`, env file `/etc/musicboxd/stack.env`.

## 0. Prerequisites (do not start without these)

- [ ] `deploy/runbook-mbd-10-cd-setup.md` is done: role `musicboxd-gha-deploy` exists, its smoke test (invalid tag, exit 2)
  passed, and `deploy.sh` is installed on the host.
- [ ] The three GitHub variables exist: **[local]** `gh variable list --repo joaovitorzanardo/musicboxd` shows
  `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `DEPLOY_INSTANCE_ID`.
- [ ] The stack is healthy before you change anything. **[host]**
  `cd /opt/musicboxd && sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml ps`
  (expect `api` healthy, `postgres` and `nginx` running), then **[local]**
  `curl -sS -o /dev/null -w "%{http_code}\n" https://musicboxd.com.br/api/v1/api-docs` (expect `200`).
- [ ] Note the tag currently live, to compare later. **[host]** `sudo grep '^IMAGE_TAG=' /etc/musicboxd/stack.env`
  (it may be `latest` from MBD-9, or absent). Write it here: `______________`
- [ ] Optional but recommended: run the deploy script's tests on Linux or WSL once, because the lock-wait case is skipped
  on Git Bash: `bash deploy/tests/test-deploy.sh` (expect `case 4 ... OK` and `deploy script tests OK`).
- [ ] Optional: lint the workflow once Docker is running, **[local]**
  `docker run --rm -v "$(pwd -W):/r" -w /r rhysd/actionlint:latest .github/workflows/publish-images.yml`
  (expect no output).

## 1. Merge to `main` and watch the run

1. Open a PR from `story/mbd-10-cd-deploy` to `main` and merge it. The merge is the push that starts the workflow.
2. **[local]** find and follow the run:

   ```bash
   gh run list --repo joaovitorzanardo/musicboxd --workflow publish-images.yml --limit 3
   gh run watch --repo joaovitorzanardo/musicboxd --exit-status
   ```

3. Expect: both `publish` legs (`api`, `web`) green, then `deploy` green. Open the `deploy` job log and check that:
   - `Final status: Success`
   - the remote stdout section ends with `Deployed sha-<the merge commit's full sha>`
4. Record the run URL and sha: `______________`

If `deploy` fails, read the "remote stdout" and "remote stderr" sections in its log first. Common causes are in
Troubleshooting at the end.

## 2. Confirm on the host

**[host]** the running images carry the new tag (the commit sha you recorded in step 1):

```bash
cd /opt/musicboxd
sudo grep '^IMAGE_TAG=' /etc/musicboxd/stack.env
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml images
sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml ps
```

Expect `IMAGE_TAG=sha-<full sha>`, `api` and `nginx` (the web image) on that tag, `api` healthy, `postgres` unchanged.

**[local]** the site and API still answer, and the certificate is untouched:

```bash
curl -sS -o /dev/null -w "%{http_code} %{ssl_verify_result}\n" https://musicboxd.com.br/
curl -sS -o /dev/null -w "%{http_code}\n" https://musicboxd.com.br/api/v1/api-docs
```

Expect `200 0` and `200`.

Also confirm the sync part of the deploy ran: **[host]** `sudo git -C /opt/musicboxd-src log -1 --format=%H` equals the merge
commit sha, and `ls -l /opt/musicboxd/deploy.sh` shows a fresh modification time.

## 3. Failure drill (rollback and red job)

Goal: a bad deploy leaves the host on the previous tag, the site up, and the job red. Use a tag that does not exist in
GHCR, so nothing real is broken. `docker compose pull` fails on it, the script rolls back and exits 1.

**[local]** run the real script through the same path CI uses (SSM), with 40 zeros as the sha:

```bash
CMD_ID=$(aws ssm send-command --region us-east-1 --instance-ids <INSTANCE_ID> --document-name AWS-RunShellScript --parameters 'commands=["cd /opt/musicboxd && ./deploy.sh sha-0000000000000000000000000000000000000000"]' --query Command.CommandId --output text)
aws ssm wait command-executed --region us-east-1 --command-id "$CMD_ID" --instance-id <INSTANCE_ID> || true
aws ssm get-command-invocation --region us-east-1 --command-id "$CMD_ID" --instance-id <INSTANCE_ID> --query "{status:Status,code:ResponseCode}" --output json
aws ssm get-command-invocation --region us-east-1 --command-id "$CMD_ID" --instance-id <INSTANCE_ID> --query StandardErrorContent --output text
```

Expect:

- `status` is `Failed`, `code` is `1`.
- stderr contains `Deploy of sha-000... failed; rolling back to sha-<the tag from step 2>` and no `Rollback also failed`.

Then confirm the host is back on the good tag and the site is up:

- **[host]** `sudo grep '^IMAGE_TAG=' /etc/musicboxd/stack.env` shows the step 2 tag again, and the `ps` command from step 2 shows
  `api` healthy.
- **[local]** `curl -sS -o /dev/null -w "%{http_code}\n" https://musicboxd.com.br/api/v1/api-docs` returns `200`.

This proves the script's rollback and that SSM surfaces the failure (`Failed`, exit code 1). The workflow turns exactly that
status into a red job (`[ "$STATUS" = Success ]`). A full workflow-red drill would need a bad image on `main`, which this
runbook deliberately does not do.

## 4. Concurrency check (deploys queue, never overlap or cancel)

Goal: while one deploy is running, a second run waits for it instead of cancelling or interleaving.

1. **[local]** start run A and wait until its `deploy` job is `in progress`:

   ```bash
   gh workflow run publish-images.yml --repo joaovitorzanardo/musicboxd --ref main
   gh run list --repo joaovitorzanardo/musicboxd --workflow publish-images.yml --limit 2
   ```

   Open run A in the browser (or `gh run view <run-id>`) and wait until `publish` is done and `deploy` is running.
2. Immediately start run B, once `deploy` of run A is running (starting it earlier would cancel run A's still-building
   `publish`, which is intended behavior but not what this test checks):

   ```bash
   gh workflow run publish-images.yml --repo joaovitorzanardo/musicboxd --ref main
   ```

3. Expect, in the Actions UI:
   - run A's `deploy` is not cancelled and finishes green;
   - run B's `publish` completes, then run B's `deploy` shows **Waiting for pending jobs** until run A's `deploy` finishes, then runs;
   - `gh run list --repo joaovitorzanardo/musicboxd --workflow publish-images.yml --limit 2` shows both `completed success`.
4. Both runs build the same commit here, so the end tag is the same; the proof is the ordering (B's `deploy` started after A's finished).
   To also prove "newest sha wins", repeat with two PRs merged back to back: the middle run's `deploy` may be replaced (GitHub keeps one
   pending run), and the final live `IMAGE_TAG` is the last merge's sha.
5. **[host]** `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml ps` shows `api` healthy, and
   `curl` from step 2 returns `200`.

The skipped lock-wait test (host-side `flock`) is covered here only if the two deploys overlapped on the host; the GitHub
`deploy-production` group normally serializes them first, so the `flock` is a second line of defense.

## 5. Update the runbook and commit

In `deploy/runbook-aws-host.md`:

1. Replace the "Deploying new images (until MBD-10)" bullet under **Operations notes** with:

   ```markdown
   - **Deploying new images (MBD-10):** automatic. A push to `main` builds the images ("Publish images" workflow), then its
     `deploy` job assumes `musicboxd-gha-deploy` over OIDC and runs `/opt/musicboxd/deploy.sh sha-<commit>` on the host over SSM.
     The script syncs `deploy/` from `origin/main`, pins `IMAGE_TAG` in `/etc/musicboxd/stack.env`, pulls, runs `up -d`, waits for
     `api` healthy and rolls back to the previous tag if it is not. Deploys are serialized (`deploy-production` group + `flock`).
   - **Manual redeploy:** Actions, "Publish images", Run workflow on `main` (rebuilds and redeploys that commit).
   - **Manual rollback:** in an SSM session, `cd /opt/musicboxd && sudo ./deploy.sh sha-<previous full sha>`
     (any tag already in GHCR). Note the next push to `main` deploys the new commit again.
   ```

2. Fill the results table:

   | Check | Result | Date |
   | ----- | ------ | ---- |
   | CD run: publish and deploy green, host on `sha-<sha>` | `TODO` | |
   | Site and API 200 after deploy | `TODO` | |
   | Failure drill: exit 1, rolled back, site 200 | `TODO` | |
   | Concurrency: second deploy waited for the first | `TODO` | |

3. Commit by file name (never `git add -A`; your working tree holds unrelated changes):

   ```bash
   git add deploy/runbook-aws-host.md deploy/runbook-mbd-10-cd-setup.md deploy/runbook-mbd-10-cd-verify.md deploy/aws/github-deploy-trust-policy.json deploy/aws/github-deploy-permissions.json
   git commit -m "Document CD setup, verification and rollback (MBD-10)"
   ```

   Do not commit `deploy/prod.env.example` if it contains a real password. If `runbook-aws-host.md` has other unreviewed edits,
   read `git diff deploy/runbook-aws-host.md` first.

When all four results are filled, MBD-10's acceptance criterion is met; move the Jira issue to Done.

## Troubleshooting

- **`deploy` fails at "Configure AWS credentials" (`Not authorized to perform sts:AssumeRoleWithWebIdentity`):** the token `sub`
  does not match the trust policy. The run must be on `refs/heads/main` of `joaovitorzanardo/musicboxd`, and the job must not
  have an `environment:`. Also check the three variables are set and `AWS_DEPLOY_ROLE_ARN` is exact.
- **`AccessDeniedException` from `ssm:SendCommand` or `GetCommandInvocation`:** the role's permissions policy does not cover this instance or
  the `AWS-RunShellScript` document; re-check `deploy/aws/github-deploy-permissions.json` and `DEPLOY_INSTANCE_ID`.
- **Status `Failed` with `No such file or directory` for `./deploy.sh`:** `deploy.sh` was not installed on the host (setup step 5).
- **Remote stderr `refusing tag`:** a tag that is not `sha-<40 hex>`; the workflow always sends `sha-${{ github.sha }}`, so this only
  appears in manual runs.
- **Remote stderr shows a failed `docker compose pull` (manifest unknown / unauthorized):** the image for that sha was not pushed
  (a `publish` leg failed or was cancelled) or the GHCR package is not public.
- **Rolled back, no obvious cause:** the script prints the last 50 log lines on failure; also **[host]**
  `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml logs --tail 100 api nginx`.
- **Job red but the host is fine (or the reverse):** the job result reflects the SSM command status. If the job hit its 20 minute
  limit or was cancelled by hand, the host script may still have finished; check `IMAGE_TAG` and `ps` on the host.
