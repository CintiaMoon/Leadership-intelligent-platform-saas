# Runbook: Operator plan change

- Status: **Draft** (written 2026-10-28, slice 006 phase 4). The script is executed against PostgreSQL by `OperatorAssignPlanTests` on every build, and was run once with real `psql` in a throwaway container (see "Test record"). It has **not been rehearsed on staging**; that rehearsal is the product owner's team's, and routine use waits for it (D-209, plan Q-15). Do not use it for a real customer before that.
- Owner role: Product owner
- Related: SPEC-PLAN (AC-PLAN-11, AC-PLAN-16, AC-PLAN-17, AC-PLAN-21), D-35, D-65, D-209, D-210, `operator-ownership-transfer.md` (same safeguards)

## When it applies
There is no billing yet (D-35): an operator moves a company to a paid plan, or to another paid plan, after the company has agreed to it outside the product. It covers:

- a company on the Trial, or whose trial has ended (read-only or locked), or with no plan, becoming a Starter or Team customer;
- a Starter company moving to Team, or the other way round;
- an ACTIVE company moving to a **newer version** of its plan (see "Creating a new plan version").

It never grants a Trial and never ends a subscription: the Trial is granted by the application for a user's first company only (D-45), and ending a plan is a later slice (D-66).

## Who does what
- **The product owner** approves the ticket. **A database operator** runs the script with a named, audited database role. They are two different people.
- **The ticket** holds: the company id, the plan (`STARTER` or `TEAM`), the requester's identity, and the evidence that they speak for the company. For a **downgrade** it also holds the numbers from "Before a downgrade" below and the requester's acknowledgement of them.

## What the script does
File: `docs/runbooks/operations/sql/operator-assign-plan.sql`. One transaction, one company at a time. The script:

1. **Checks the inputs** and stops with an error, committing nothing, if the ticket is malformed (letters, digits, `-`, `_`, up to 40), the plan is anything but exactly `STARTER` or `TEAM` (`TRIAL`, lower case and unknown codes are refused), or the company id is not a lower-case UUID. It then **refuses to go on unless it is running inside one transaction**: without `--single-transaction` every statement would commit on its own and the locks would be released between the checks and the writes.
2. **Locks** the company row first (the application's lock order, D-32, D-125), then the subscription row. It gives up on a lock after 5 seconds; run it again. While the company lock is held no member change, invitation or ownership transfer can be in flight in that company, and every decision the application takes afterwards reads the new plan.
3. **Checks the state** and stops with an error if the company does not exist, has no subscription row (a data fault: the script does not repair it), or the plan is not in the catalog. It uses the **newest version** of the plan code (AC-PLAN-16). Companies on a plan keep the version they have until an operator moves them.
4. **Changes** the subscription: state `ACTIVE`, the plan, `state_changed_at`, `version`. The trial dates stay on the row as history; the API does not show them for an ACTIVE company and the sweep ignores it. The change is effective **at once**, including for a company whose trial had ended but whose end the sweep had not yet recorded. It changes nothing when the company is already ACTIVE on the newest version of that plan: a second run writes no audit event (AC-PLAN-11).
5. **Audits** with `actor_type = 'operator'`. The reasons hold the ticket and the database login that ran the script, `session_user` (and the role it switched to, if that differs), because operators have no user ID yet:
   - `plan.subscription.transitioned`: before and after state, reason `operator_assigned` (the company was not ACTIVE) or `operator_changed` (it was).
   - `plan.entitlement.changed`, only when the plan row changes: before and after plan **code** (`TRIAL`, `STARTER`, `TEAM`; empty before for a company with no plan), the versions in the reason (`from_version=1 to_version=1`).
   No address and no name is written.
6. **Checks the result and stops if it is wrong**: it prints state, plan, version, the plan's member limit and the number of ACTIVE members, then a last guard rolls everything back unless the company is ACTIVE on the newest version of the plan.

## Before a downgrade
A downgrade removes nothing and suspends nobody (plan Q-18). If the company has more ACTIVE members than the new plan allows, they all stay, but **every reactivation of a member and every acceptance of an invitation is refused with `MEMBER_LIMIT_REACHED` until usage is below the limit**. Tell the requester before you run it. Look at the numbers first:

```
psql -c "SELECT (SELECT count(*) FROM membership WHERE company_id = '<company id>' AND status = 'ACTIVE') AS active_members, l.value AS new_limit FROM plan p JOIN plan_limit l ON l.plan_id = p.id AND l.limit_key = 'ACTIVE_MEMBERS' WHERE p.code = '<STARTER or TEAM>' ORDER BY p.version DESC LIMIT 1"
```

Starter allows 15 active members and Team 100 (version 1). Teams and assessments have limits too, but their slices do not exist yet, so there is nothing to count.

## Creating a new plan version
The catalog is immutable (AC-PLAN-16): a database trigger refuses any update or delete of a plan, its limits or its features, and refuses new limits or features for a plan some company already uses. A change of limits or features is a **new version row**, added by a reviewed Flyway migration (`plan`, `plan_limit`, `plan_feature`, version = the previous one plus 1), released like any migration. Nobody edits the catalog by hand in production.

The new version reaches nobody by itself. To move a company to it, run this script for that company with the same plan code: it picks the newest version and records `operator_changed` and `plan.entitlement.changed` with `from_version` and `to_version`.

## Procedure
1. Get the ticket approved (see "Who does what"). Confirm the company id and the plan from the ticket. For a downgrade, run the check above and put the numbers in the ticket.
2. Take a backup point as for any production change (`restore-from-backup.md`), and look at the company first:
   ```
   psql -c "SELECT s.state, p.code, p.version, s.trial_ends_at, s.read_only_ends_at FROM company_subscription s LEFT JOIN plan p ON p.id = s.plan_id WHERE s.company_id = '<company id>'"
   ```
3. Run the script in **one transaction**. All three variables are required; a missing one makes the statement fail and nothing is committed.
   ```
   psql --single-transaction --set ON_ERROR_STOP=1 \
        -v company=<company uuid> -v plan=TEAM -v ticket=BILL-42 \
        -f docs/runbooks/operations/sql/operator-assign-plan.sql
   ```
4. Read the last output: `state` must be `ACTIVE`, `plan` and `plan_version` the ones intended. The script checks this itself and rolls back with an error if it is not so. Never run the file without `--single-transaction`: it stops at once if you do.
5. Confirm after the commit: the audit rows exist (below), and the company's Owner sees the new plan on the plan page and can use what the plan allows.
   ```
   psql -c "SELECT occurred_at, event_type, before_value, after_value, reason_code FROM audit_event WHERE company_id = '<company id>' AND event_type IN ('plan.subscription.transitioned', 'plan.entitlement.changed') ORDER BY occurred_at DESC LIMIT 5"
   ```
6. Record the ticket, the date and the result. Tell the requester through the channel in the ticket.

## If it goes wrong
- **An error from the script:** nothing was changed. Read the error: a division by zero names the failed condition in the column alias of the query (`ticket_ok`, `plan_is_starter_or_team`, `company_is_a_lower_case_uuid`, `inside_one_transaction`, `company_exists`, `subscription_exists`, `plan_exists_in_the_catalog`, `assignment_is_consistent`). Fix the input or the ticket, never the script.
- **A lock timeout:** a long application transaction holds the company. Wait a minute and run it again.
- **The wrong plan, found after the commit:** open a new ticket and run the script again with the right plan. Both changes stay in the audit trail. Do not edit `company_subscription` by hand: its triggers refuse the changes the state machine does not allow, and a hand edit would leave no audit row.
- **A company the script refuses because it has no subscription row:** this should not happen (every company gets one when it is created, and the migration gave the existing ones `NO_PLAN`). Do not insert one by hand. Raise it with the engineering owner.

## Gaps to close
- There is no operator endpoint or operator identity; the audit rows carry the ticket and the database role (see `operator-account-disable.md`).
- There is no way to end or cancel a paid plan, and no `PAST_DUE` or `CANCELED` state: they belong to the billing slice (D-66).
- The ticket is a checked string, not a proof: nothing in the database shows that it exists or was approved, so the two-person rule is procedural.
- Not tested: a lock timeout of exactly 5 seconds (the lock-wait test proves the order of the locks, not the timeout value). The guards `plan_exists_in_the_catalog` and the transition check, and the final consistency guard, cannot be made to fail on their own through the database (the whitelist, the check constraints and the transition trigger stop first); they are second lines, and the mutation checks recorded in the traceability file say so.
- The script counts only members. When the team and assessment slices exist, the downgrade check must show their usage too.

## Test record
Automated: `OperatorAssignPlanTests` (15 tests against PostgreSQL in every build; its mutation checks are listed in `docs/specs/006-plans-entitlements/traceability.md`).

Manual: see the table below. The staging rehearsal has **not** been done; record its date, the tester and the result here.

| Date | Environment | Tester | Result |
|---|---|---|---|
| 2026-10-28 | local `psql` 17.6 inside a throwaway `postgres:17.6` container, migrations V1 to V15 applied | Claude (assistant) | Passed: a bad ticket, a missing variable and a run without `--single-transaction` stop the script with nothing written; a company with no subscription row is refused; NO_PLAN to TEAM, a repeat (no new audit rows) and TEAM to STARTER ended ACTIVE on the intended plan with 4 audit rows (`operator_assigned`, `operator_changed`, two `plan.entitlement.changed`). The application was not running, so effects through the API were not checked |
| (pending) | staging | product owner's team | not done |
