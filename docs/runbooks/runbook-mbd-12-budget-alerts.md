# Runbook: MBD-12 AWS Budgets alerts at 50/80/100% (console)

Goal: one monthly cost budget for the account that emails the owner when actual spend reaches 50%, 80% and
100% of the limit. One-time, human-run setup in the AWS console (billing is not something the agent touches).

Values (from `runbook-aws-host.md`):

| Item | Value |
| ---- | ----- |
| AWS account | `<ACCOUNT_ID>` |
| Budget name | `musicboxd-monthly` |
| Period | Monthly, recurring |
| Monthly limit | `<USD_LIMIT>` — **decide before starting** (e.g. 10 USD) |
| Alert email | `joaovitorzanardoorg@gmail.com` |

Budgets is a global service; the console shows "Global" in the region selector. It does not matter that the
host is in `us-east-1`.

## 0. Pre-flight

- [ ] Signed in to account `<ACCOUNT_ID>` (account menu, top right).
- [ ] You are the root user, **or** an IAM user/role with `budgets:*` permissions. If you are an IAM user and the
  Billing pages say "You don't have permission", the root user must first enable
  **Account (top right) → Account → IAM user and role access to Billing information → Activate IAM Access**.
- [ ] The email inbox is reachable. Budgets sends from `no-reply@budgets.awscloudservices.com` (check spam).
- [ ] Free tier note: the first 2 budgets per account are free. Do not create more than 2 (this one + the
  temporary test budget in step 3).

## 1. Create the budget

1. Console → search **Billing and Cost Management** → left menu **Budgets** → **Create budget**.
2. **Budget setup**: choose **Customize (advanced)** → **Cost budget – Recommended** → **Next**.
   (Do not use "Use a template", it only offers a zero-spend budget.)
3. **Details**:
   - Budget name: `musicboxd-monthly`
   - Period: **Monthly**
   - Budget renewal type: **Recurring budget**
   - Start month: current month
   - Budgeting method: **Fixed**
   - Enter your budgeted amount: `<USD_LIMIT>`
4. **Budget scope**: **All AWS services (Recommended)**. Leave the advanced filters alone.
5. Under **Advanced options → Aggregate costs by**, keep **Unblended costs**. Leave the "Include" checkboxes at
   defaults (credits and refunds are excluded from the total by default; leave as is).
6. **Next** to go to *Configure alerts*.

## 2. Configure the three alerts

Add three alert thresholds. Repeat **Add an alert threshold** twice so there are three blocks.

| # | Set alert threshold | Threshold | Trigger | Email recipients |
| - | ------------------- | --------- | ------- | ---------------- |
| 1 | Alert threshold | `50` **% of budgeted amount** | **Actual** | `joaovitorzanardoorg@gmail.com` |
| 2 | Alert threshold | `80` **% of budgeted amount** | **Actual** | `joaovitorzanardoorg@gmail.com` |
| 3 | Alert threshold | `100` **% of budgeted amount** | **Actual** | `joaovitorzanardoorg@gmail.com` |

Notes:

- In each block the unit dropdown must read **% of budgeted amount** (not "Absolute value").
- Leave **Amazon SNS Alerts** and **AWS Chatbot** empty; email is enough for this story.
- Optional: add a 4th block, `100%` with trigger **Forecasted**, to be warned before the limit is actually hit.
  It is not in the acceptance criteria, so skip it unless you want it.

Click **Next** → **Attach actions** (skip, leave empty) → **Next** → review the summary:
name, `<USD_LIMIT>` monthly, three alerts at 50/80/100 → **Create budget**.

## 3. Verify (acceptance criteria: a threshold crossing sends an email)

You cannot "send a test email" from the Budgets console, and a real 50% crossing may be weeks away. Simulate it
with a temporary budget whose limit is already below current spend.

1. Open **Budgets → Cost Explorer** (or the budget's own page) and note month-to-date cost. It must be above
   0 USD for the test to fire. The EC2 host should guarantee that after a day or so; if it shows `$0.00`, wait.
2. **Create budget** → Customize → Cost budget → **Next**:
   - Name: `musicboxd-alert-test` (delete it afterwards)
   - Period **Monthly**, Recurring, Fixed, amount **`0.01`**
   - Scope: all AWS services
3. Alert: **Actual**, threshold `50` **% of budgeted amount**, recipient `joaovitorzanardoorg@gmail.com`
   → **Create budget**.
4. Wait for the next budget evaluation. AWS refreshes billing data and evaluates budgets up to **3 times a day**
   (roughly every 8 hours), so the email can take up to ~24 h. The budget page shows status **Exceeded** (red)
   when it has evaluated.
5. Confirm the email arrives with a subject like
   `AWS Budgets: musicboxd-alert-test has exceeded your alert threshold`. Screenshot or copy it for the Jira ticket.

If no email arrives after 24 h, see Troubleshooting.

## 4. Clean up the test budget

- [ ] **Budgets** → select `musicboxd-alert-test` → **Actions → Delete budget** → confirm.
- [ ] Confirm only `musicboxd-monthly` remains and its three alerts show 50 / 80 / 100%
  (open it → **Alerts** tab).

## 5. Close out MBD-12

Attach to the Jira ticket: screenshot of the `musicboxd-monthly` Alerts tab, and the test alert email.
Then transition MBD-12 to Done.

## Troubleshooting

| Symptom | Cause / fix |
| ------- | ----------- |
| Billing pages show "not authorized" | IAM billing access not activated (step 0), or user lacks `budgets:*`. |
| No email after 24 h | Check spam; confirm the address in the alert block has no typo; confirm month-to-date cost really exceeds `0.01`; confirm the test alert was **Actual**, not Forecasted. |
| Budget shows `$0.00` actual | Billing data lags up to 24 h; wait, or keep the host running. Credits are not counted, so a fully credit-covered account will show 0 actual cost — in that case set the test limit to `0.01` with the *Include credits* box ticked in step 1.5 advanced options. |
| "Budget limit reached" creating a 3rd budget | Only 2 free budgets; delete the test budget first. |

## Rollback

Budgets only sends emails; it never stops resources. To undo: **Budgets → select `musicboxd-monthly` →
Actions → Delete budget**.
