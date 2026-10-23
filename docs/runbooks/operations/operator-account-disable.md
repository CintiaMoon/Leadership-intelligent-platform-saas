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
Run the reviewed script in **one transaction** on the primary database, with the account's normalized address (lower case) as the variable:

```
psql --single-transaction --set ON_ERROR_STOP=1 -v email=person@example.com \
     -f docs/runbooks/operations/sql/disable-account-and-flag-companies.sql
```

The script has five steps: disable the account (and audit it), delete its sessions, lock the company rows it owns, suspend its Owner memberships and flag those companies (and audit both), and print a check. It is idempotent: a second run changes nothing and writes no second set of audit rows. An address that matches no account changes nothing.

Check the output:
- Step 3 lists the companies the account owns. Zero rows means it owns none (only the disable and the session deletion apply).
- Step 5 shows, for each of those companies, a non-null `ownership_resolution_at` and `owner_memberships = 1`. Anything else: stop and do not commit (`ON_ERROR_STOP` and `--single-transaction` roll back on an error; if you ran it interactively, `ROLLBACK`).

Then confirm: the user's next request returns 401, a login attempt returns the same generic failure as a wrong password, and an Admin of an affected company can still open the member list.

## Revoke sessions without disabling (suspected stolen session)
Run only the `DELETE FROM spring_session ...` statement (step 2 of the script). The user stays able to log in again. If the password may be known to the attacker, also require a password reset (the user can use the forgot-password page, which signs out every device).

## Re-enabling
`UPDATE app_user SET status = 'ACTIVE' WHERE email_normalized = '...';` Sessions are not restored; the user logs in again.

**Re-enabling does not undo the company effects.** An Owner membership that the procedure suspended stays suspended and the company stays flagged. Returning the Owner, or transferring ownership, needs a decision by the security owner and a separate reviewed script; none exists yet. Do not improvise one with ad-hoc `UPDATE`s: the Owner invariant (exactly one Owner membership per company) is enforced by the database, and a mistake there is a data incident.

## Gaps to close
- There is no operator endpoint or operator identity; the audit rows carry no operator ID. Add them in the slice that introduces operators.
- The transfer to an active member (slice 005) and the reversal above are not written.
- Members' plan counts still include a disabled user's membership (slice 006 must decide).
- Disabling does not clear `login_throttle` state; that is intended.

## Test record
Automated: `OwnerAccountDisableProcedureTests` (runs the script file against PostgreSQL in every build). Manual rehearsal in a deployed environment: none yet. Record the date, the tester, and the result after the first rehearsal.
