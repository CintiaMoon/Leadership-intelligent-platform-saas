# Runbook: Operator account disable and session revocation

- Status: Draft (updated 2026-10-23 for slice 003). The SQL file is executed against PostgreSQL by the integration test `OwnerAccountDisableProcedureTests` on every build. It has **not been rehearsed** in a deployed environment and there is no operator API yet. Do not rely on it for a real incident without a rehearsal.
- Owner role: Security owner
- Related: SPEC-AUTH AC-AUTH-07, SPEC-MEM (AC-MEM-14, AC-MEM-18), SPEC-OWN, D-22, D-54

## What happens when an account is disabled
- The session filter checks the account status on every request. A `DISABLED` account is rejected at once, even before its sessions are deleted, and cannot log in. Deleting the sessions then frees the rows.
- **Companies the account owns (D-54).** Each such company keeps exactly one Owner membership, which becomes `SUSPENDED` (the role stays `OWNER`), and is flagged with `company.ownership_resolution_at`. A suspended Owner has no access, nobody else holds an Owner-only permission, so company deletion, ownership transfer by the Owner, Admin promotion, plan changes and every other Owner-only operation are unavailable. Admins, Agents and Viewers keep working. The company stays like this until an audited, operator-assisted transfer to an active member (slice 005 and `operator-ownership-transfer.md`, still a stub).
- **Companies where the account is only a member.** Their memberships stay `ACTIVE`; the account simply cannot use them while disabled.
- The procedure writes audit rows with `actor_type = 'operator'`: `account.status.changed`, `membership.suspended` and `company.ownership_resolution.flagged` (reason `owner_account_disabled`). The operator is identified by the ticket you record, not by a user ID.

## Who may do it
The security owner approves; a database operator runs it with a named, audited role. Record the ticket and the reason.

## Procedure
Run the reviewed script in **one transaction** on the primary database. Both variables are required:

```
psql --single-transaction --set ON_ERROR_STOP=1 -v email=person@example.com -v ticket=SEC-123 \
     -f docs/runbooks/operations/sql/disable-account-and-flag-companies.sql
```

- `email` is trimmed and lower-cased by the script. An address that matches no account **stops the script with an error and commits nothing** (a typo cannot look like success).
- `ticket` is the approval ticket (letters, digits, `-`, `_`, up to 40). It is written into every audit row together with the database role that ran the script, because operators have no user ID yet. A missing or malformed ticket stops the script.
- The script gives up on a lock after 5 seconds instead of queuing behind a long application transaction; run it again.

It does, in this order: check the inputs; lock the company rows the account owns (before touching the account, the same order as the application, D-125); disable the account and audit it; delete its sessions; suspend its Owner memberships and flag those companies, with audit rows; print a check. It is idempotent: a second run changes nothing and writes no second set of audit rows.

Check the output:
- Step 1 lists the companies the account owns. Zero rows means it owns none (only the disable and the session deletion apply).
- The last query shows, for each of those companies, a non-null `ownership_resolution_at` and `owner_memberships = 1`. Anything else: stop and do not commit (`ON_ERROR_STOP` and `--single-transaction` roll back on an error; if you ran it interactively, `ROLLBACK`).

Then confirm: the user's next request returns 401, a login attempt returns the same generic failure as a wrong password, and an Admin of an affected company can still open the member list.

## Revoke sessions without disabling (suspected stolen session)
Run only the `DELETE FROM spring_session ...` statement (step 3 of the script). The user stays able to log in again. If the password may be known to the attacker, also require a password reset (the user can use the forgot-password page, which signs out every device).

## Re-enabling
`UPDATE app_user SET status = 'ACTIVE' WHERE email_normalized = '...';` Sessions are not restored; the user logs in again.

**Re-enabling does not undo the company effects.** An Owner membership that the procedure suspended stays suspended and the company stays flagged. Returning the Owner, or transferring ownership, needs a decision by the security owner and a separate reviewed script; none exists yet. Do not improvise one with ad-hoc `UPDATE`s: the Owner invariant (exactly one Owner membership per company) is enforced by the database, and a mistake there is a data incident.

## Gaps to close
- There is no operator endpoint or operator identity; the audit rows carry the ticket and the database role instead of an operator ID. Add real operators in the slice that introduces them.
- The transfer to an active member (slice 005) and the reversal above are not written.
- Memberships of the disabled account in companies it does not own stay `ACTIVE`: they appear in member lists, count toward plan limits, and become usable again if the account is re-enabled. Decision D-141 (open).
- A re-enabled Owner stays suspended and nobody can reactivate the membership (an Admin may not act on an Owner). Decision D-142 (open).
- Disabling does not clear `login_throttle` state; that is intended.

## Test record
Automated: `OwnerAccountDisableProcedureTests` (runs the script file against PostgreSQL in every build). Manual rehearsal in a deployed environment: none yet. Record the date, the tester, and the result after the first rehearsal.
