# Runbook: MBD-10 one-time setup for CD (plan Task 2)

Goal: let the GitHub Actions `deploy` job (plan Task 3) run `/opt/musicboxd/deploy.sh <tag>` on the EC2 host
through SSM, with **no static AWS keys** and **no SSH**. This is a one-time, human-run setup; it touches IAM,
so the agent does not do it for you.

Values used below (from `runbook-aws-host.md`):

| Item | Value |
| ---- | ----- |
| AWS account | `<ACCOUNT_ID>` |
| Region | `us-east-1` |
| Instance ID | `<INSTANCE_ID>` |
| GitHub repo | `joaovitorzanardo/musicboxd` |
| New IAM role | `musicboxd-gha-deploy` |
| Policy files (repo) | `deploy/aws/github-deploy-trust-policy.json`, `deploy/aws/github-deploy-permissions.json` |

Commands are for a shell with the AWS CLI configured for this account (Git Bash, or PowerShell: the `file://`
paths work in both). Run them from the repo root. Use an admin-capable identity (your own IAM user / SSO).

## 0. Pre-flight

- [ ] `aws sts get-caller-identity` shows account `<ACCOUNT_ID>`.
- [ ] The SSM agent on the host is online:

  ```bash
  aws ssm describe-instance-information --region us-east-1 --query "InstanceInformationList[?InstanceId=='<INSTANCE_ID>'].PingStatus" --output text
  # expect: Online
  ```

- [ ] The repo is public (the host fetches it with no credentials):

  ```bash
  git ls-remote https://github.com/joaovitorzanardo/musicboxd.git HEAD
  # expect: a commit hash, with no username prompt
  ```

  If this prompts for credentials, the repo is private: stop and set up a read-only deploy key on the host first.
- [ ] The GHCR packages are public (MBD-9 decision). From any machine with Docker, **logged out of ghcr.io**:

  ```bash
  docker logout ghcr.io
  docker manifest inspect ghcr.io/joaovitorzanardo/musicboxd-api:latest > /dev/null && echo public
  ```

- [ ] The MBD-10 branch is merged to `main` (open a PR from `story/mbd-10-cd-deploy`). Step 5 needs
  `deploy.sh` on `origin/main`. Do not merge the Task 3 workflow change before step 6 is done, or the first run
  of `deploy` will fail (that is harmless, but red).

## 1. GitHub OIDC identity provider (once per AWS account)

Check whether it already exists:

```bash
aws iam list-open-id-connect-providers
# look for ...:oidc-provider/token.actions.githubusercontent.com
```

If it is absent, create it:

```bash
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com
```

AWS validates GitHub's certificate chain itself, so no thumbprint is required. If the provider already exists,
skip this step and do not create a second one.

## 2. Create the role (trust policy)

`deploy/aws/github-deploy-trust-policy.json` lets only workflow runs on **`main`** of
`joaovitorzanardo/musicboxd` assume the role (`sub` = `repo:joaovitorzanardo/musicboxd:ref:refs/heads/main`,
`aud` = `sts.amazonaws.com`). Pull requests and other branches cannot assume it.

```bash
aws iam create-role --role-name musicboxd-gha-deploy --description "GitHub Actions CD for musicboxd (MBD-10): SSM RunCommand on the host only" --max-session-duration 3600 --assume-role-policy-document file://deploy/aws/github-deploy-trust-policy.json
```

Note: this repo uses GitHub's **immutable subject claim** (`gh api repos/joaovitorzanardo/musicboxd/actions/oidc/customization/sub` returns
`use_immutable_subject: true`), so the token `sub` is `repo:joaovitorzanardo@100529680/musicboxd@1388306403:ref:refs/heads/main`, not
`repo:joaovitorzanardo/musicboxd:ref:refs/heads/main`. The trust policy lists both. If the role already exists, update it with
`aws iam update-assume-role-policy --role-name musicboxd-gha-deploy --policy-document file://deploy/aws/github-deploy-trust-policy.json`.

Note: if the `deploy` job later gets a GitHub `environment:`, the token's `sub` changes
(`...:environment:<name>`) and this trust policy must change with it.

## 3. Attach the permissions policy

`deploy/aws/github-deploy-permissions.json` allows exactly: `ssm:SendCommand` on the `AWS-RunShellScript`
document and on this one instance, plus `ssm:GetCommandInvocation` / `ssm:ListCommandInvocations`
(these two do not support resource scoping, hence `*`). Nothing else.

```bash
aws iam put-role-policy --role-name musicboxd-gha-deploy --policy-name musicboxd-gha-deploy-ssm --policy-document file://deploy/aws/github-deploy-permissions.json

aws iam get-role --role-name musicboxd-gha-deploy --query Role.Arn --output text
# record this ARN: arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-gha-deploy
```

## 4. Verify the role by simulation (no deploy needed)

```bash
ROLE=arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-gha-deploy
INSTANCE=arn:aws:ec2:us-east-1:<ACCOUNT_ID>:instance/<INSTANCE_ID>
DOC=arn:aws:ssm:us-east-1::document/AWS-RunShellScript

aws iam simulate-principal-policy --policy-source-arn $ROLE --action-names ssm:SendCommand --resource-arns $INSTANCE $DOC --query "EvaluationResults[].[EvalActionName,EvalResourceName,EvalDecision]" --output table
# expect: allowed for both resources

aws iam simulate-principal-policy --policy-source-arn $ROLE --action-names ssm:SendCommand --resource-arns arn:aws:ec2:us-east-1:<ACCOUNT_ID>:instance/i-00000000000000000 --query "EvaluationResults[].EvalDecision" --output text
# expect: implicitDeny (other instances)

aws iam simulate-principal-policy --policy-source-arn $ROLE --action-names ec2:DescribeInstances s3:GetObject iam:ListUsers ssm:StartSession --resource-arns "*" --query "EvaluationResults[].[EvalActionName,EvalDecision]" --output table
# expect: all implicitDeny
```

(Under Git Bash on Windows, a leading `/` in an argument can be rewritten; none of the ARNs above start with one.)

## 5. Install `deploy.sh` on the host (first time only)

Why by hand: the CD job runs `cd /opt/musicboxd && ./deploy.sh <tag>`, and `deploy.sh` only lands in
`/opt/musicboxd` through its own sync. The first copy has to be placed manually. After this, every deploy
refreshes it from `origin/main`.

```bash
aws ssm start-session --target <INSTANCE_ID> --region us-east-1
```

On the host:

```bash
sudo git -C /opt/musicboxd-src fetch origin
sudo git -C /opt/musicboxd-src checkout --detach origin/main
test -f /opt/musicboxd-src/deploy/deploy.sh && echo "deploy.sh present on main"

sudo install -m 755 /opt/musicboxd-src/deploy/deploy.sh /opt/musicboxd/deploy.sh
head -1 /opt/musicboxd/deploy.sh | od -c | head -2     # expect #!/usr/bin/env bash then \n, no \r
which flock docker git                                 # all three must resolve
```

Check the state `deploy.sh` depends on:

```bash
sudo grep -c '^IMAGE_TAG=' /etc/musicboxd/stack.env    # 1 is fine; 0 is also fine (script appends it)
sudo docker compose --env-file /etc/musicboxd/stack.env -f /opt/musicboxd/docker-compose.prod.yml ps
# expect: api healthy, postgres and nginx running (the stack from MBD-9 is up)
```

Do not run the script with a real tag by hand here; the first real deploy is Task 4.

## 6. Set the GitHub repository variables

These are not secrets (a role ARN and an instance ID grant nothing without the OIDC token), so use
**variables**, not secrets. With the GitHub CLI:

```bash
gh variable set AWS_DEPLOY_ROLE_ARN  --repo joaovitorzanardo/musicboxd --body "arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-gha-deploy"
gh variable set AWS_REGION           --repo joaovitorzanardo/musicboxd --body "us-east-1"
gh variable set DEPLOY_INSTANCE_ID   --repo joaovitorzanardo/musicboxd --body "<INSTANCE_ID>"
gh variable list --repo joaovitorzanardo/musicboxd
```

Or in the UI: repo **Settings, Secrets and variables, Actions, Variables tab, New repository variable**.

## 7. End-to-end smoke test of the SSM path (safe, deploys nothing)

Runs `deploy.sh` with a deliberately invalid tag. It must exit **2** before touching any file, which proves SSM
delivery, the script path and the exec bit, without changing the running stack. Run it with your own admin
identity (the role itself can only be assumed from GitHub):

```bash
CMD_ID=$(aws ssm send-command --region us-east-1 --instance-ids <INSTANCE_ID> --document-name AWS-RunShellScript --parameters 'commands=["cd /opt/musicboxd && ./deploy.sh sha-smoketest"]' --query Command.CommandId --output text)

aws ssm wait command-executed --region us-east-1 --command-id "$CMD_ID" --instance-id <INSTANCE_ID> || true
aws ssm get-command-invocation --region us-east-1 --command-id "$CMD_ID" --instance-id <INSTANCE_ID> --query "{status:Status,code:ResponseCode,err:StandardErrorContent}" --output json
# expect: code 2 and err: "refusing tag 'sha-smoketest' (want sha-<40 hex>)"
```

Status will read `Failed` because the exit code is non-zero; that is the expected result here.

## 8. Record the results

Add this to `deploy/runbook-aws-host.md` (new "MBD-10: CD" section), and fill the table:

| Item | Value |
| ---- | ----- |
| OIDC provider | `arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com` |
| Role | `arn:aws:iam::<ACCOUNT_ID>:role/musicboxd-gha-deploy` (assumable only from `refs/heads/main`) |
| GitHub variables | `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `DEPLOY_INSTANCE_ID` |
| Host script | `/opt/musicboxd/deploy.sh` (mode 755), source `/opt/musicboxd-src` |

| Check | Result | Date |
| ----- | ------ | ---- |
| Pre-flight (SSM online, repo public, GHCR public) | `TODO` | |
| Simulation: SendCommand allowed on instance + document only | `TODO` | |
| Simulation: other instances and other actions implicitDeny | `TODO` | |
| Smoke test exit code 2 over SSM | `TODO` | |

Then commit the two JSON files and the runbook update (never `git add -A`: your working tree has unrelated
changes, including a `deploy/prod.env.example` edit that must not be committed with a real password).

## Troubleshooting

- **`InvalidInstanceId` / `Instance not in a valid state` on send-command:** SSM agent offline. On the host,
  `sudo systemctl status amazon-ssm-agent`; check the instance role still has `AmazonSSMManagedInstanceCore`.
- **`AccessDenied` on `AssumeRoleWithWebIdentity` in the workflow later:** the token `sub` does not match.
  Causes: run was not on `main`, repo renamed or owner changed, or an `environment:` was added to the job.
- **Smoke test prints `No such file or directory`:** step 5 was skipped or `deploy.sh` is not on `origin/main` yet.
- **`$'\r': command not found` from the script:** CRLF endings; re-install from `origin/main` (the repo forces LF
  for `*.sh` via `.gitattributes`).

## Undo

```bash
aws iam delete-role-policy --role-name musicboxd-gha-deploy --policy-name musicboxd-gha-deploy-ssm
aws iam delete-role --role-name musicboxd-gha-deploy
gh variable delete AWS_DEPLOY_ROLE_ARN --repo joaovitorzanardo/musicboxd   # likewise AWS_REGION, DEPLOY_INSTANCE_ID
```

Leave the OIDC provider alone if anything else in the account uses it.
