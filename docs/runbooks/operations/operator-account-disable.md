# Runbook: Operator account disable and session revocation

- Status: Draft (updated 2026-10-24 for slice 003). Both SQL files are executed against PostgreSQL by the integration tests `OwnerAccountDisableProcedureTests` and `OwnerAccountReversalProcedureTests` on every build. It has **not been rehearsed** in a deployed environment and there is no operator API yet. Do not rely on it for a real incident without a rehearsal.
- Owner role: Security owner
- Related: SPEC-AUTH AC-AUTH-07, SPEC-MEM (AC-MEM-14, AC-MEM-18), SPEC-OWN, D-22, D-54

## What happens when an account is disabled
- The session filter checks the account status on every request. A `DISABLED` account is rejected at once, even before its sessions are deleted, and cannot log in. Deleting the sessions then frees the rows.
- **Companies the account owns (D-54).** Each such company keeps exactly one Owner membership, which becomes `SUSPENDED` (the role stays `OWNER`), and is flagged with `company.ownership_resolution_at`. A suspended Owner has no access, nobody else holds an Owner-only permission, so company deletion, ownership transfer by the Owner, Admin promotion, plan changes and every other Owner-only operation are unavailable. Admins, Agents and Viewers keep working. The company stays like this until an audited, operator-assisted transfer to an active member (`operator-ownership-transfer.md`, with its script).
- **Companies where the account is only a member.** Their memberships stay `ACTIVE` by decision (D-141): the session filter and the account status already deny every tenant, re-enabling the account restores access, and the memberships keep using a plan seat.
- The procedure writes audit rows with `actor_type = 'operator'`: `account.status.changed`, `membership.suspended` and `company.ownership_resolution.flagged` (reason `owner_account_disabled`). The operator is identified by the ticket you record, not by a user ID.

## Production gate (D-142)
- **Routine use waits** until the operator transfer runbook is written, the disable, reversal and transfer scripts have been rehearsed with real `psql` on staging (record it under "Test record"), and the single-member rule below is in force.
- **Emergency use is allowed** (for example a compromised Owner) with the security owner's recorded approval. The ticket must state that the company stays in Admin-only mode (Owner-only operations unavailable) until the transfer runbook exists, and that the reversal script is the only way back before then.

## Companies left flagged
List them, oldest first, with the number of active members who could take over:

```
psql -f docs/runbooks/operations/sql/list-flagged-companies.sql
```

A company whose Owner was its only member shows `other_active_members = 0`. It stays flagged and retained: nobody can use it, and the reversal script still works if the disable was a mistake. There is no deletion or invitation path for it; deleting after a retention period is a product and legal decision (D-40, D-100). A deletion that was already pending when the Owner was disabled must not be purged while the company is flagged (D-143, slice 007).

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

It does, in this order: check the inputs; lock the company rows the account owns (before touching the account, the same order as the application, D-125); disable the account and audit it; delete its sessions; suspend its Owner memberships and flag those companies, with audit rows; print a check. It is idempotent: a second run changes nothing and writes no second set of audit rows. It stops with an error if it is not run inside one transaction (see below).

Check the output:
- Step 1 lists the companies the account owns. Zero rows means it owns none (only the disable and the session deletion apply).
- The last query shows, for each of those companies, a non-null `ownership_resolution_at` and `owner_memberships = 1`. Anything else: stop and do not commit (`ON_ERROR_STOP` and `--single-transaction` roll back on an error; if you ran it interactively, `ROLLBACK`).

Then confirm: the user's next request returns 401, a login attempt returns the same generic failure as a wrong password, and an Admin of an affected company can still open the member list.

## Revoke sessions without disabling (suspected stolen session)
Run only the `DELETE FROM spring_session ...` statement (step 3 of the script). The user stays able to log in again. If the password may be known to the attacker, also require a password reset (the user can use the forgot-password page, which signs out every device).

## Both scripts must run in one transaction
Run every script in this runbook with `psql --single-transaction --set ON_ERROR_STOP=1`. Since 2026-10-29 the disable and reversal scripts also quit before doing anything if `ON_ERROR_STOP` is not on (their guards stop them only by raising an error, and psql carries on after an error unless it is set). Since 2026-10-27 each of them checks this itself: a marker statement and a guard right after the input checks stop the script, before anything is written, if it is not running inside one transaction (in autocommit mode every statement would commit on its own, the locks would be released between the checks and the writes, and the 5-second lock timeout would not apply). Each script also ends with a guard that rolls everything back unless the result is consistent (disable: every company the account owns is flagged and still has exactly one Owner membership; reversal: the account is ACTIVE and every company where it still holds the Owner role has exactly one). The audit rows record the database login (`session_user`) and, if it differs, the role it switched to (`role=`), not only `current_user`.

## Undoing a mistaken disable (D-142)
Use the reviewed reversal script. Do **not** re-enable the account with a plain `UPDATE`: that leaves an Owner membership suspended and the company flagged, and no application path can reactivate an Owner.

```
psql --single-transaction --set ON_ERROR_STOP=1 -v email=person@example.com -v ticket=SEC-124 \
     -f docs/runbooks/operations/sql/reverse-account-disable.sql
```

`ticket` is the approval ticket of the reversal. In one transaction the script: checks the inputs (an unknown address or a malformed ticket stops it and commits nothing); locks the company rows (5-second timeout); restores each Owner membership to `ACTIVE` and clears its flag, with audit rows (`membership.reactivated`, `company.ownership_resolution.cleared`, reason `owner_account_restored`, ticket and database role); re-enables the account (`account.status.changed`); and prints a check. Sessions are not restored: the user logs in again.

It restores an Owner membership only if all of these hold, so it cannot undo anything else:
- the membership still has the Owner role (so no ownership transfer has happened since);
- it is `SUSPENDED` and its company is still flagged;
- the audit trail holds the operator suspension written by the disable script for exactly this membership.

If the check shows a company still flagged, it was not restored (for example ownership was transferred): do not force it; follow the operator ownership transfer instead. Idempotent: a second run changes nothing.

## Gaps to close
- There is no operator endpoint or operator identity; the audit rows carry the ticket and the database role instead of an operator ID. Add real operators in the slice that introduces them.
- The operator transfer to an active member (slice 005, `operator-ownership-transfer.md`) is written and tested (D-142, `operator-ownership-transfer.md`) but not yet rehearsed on staging, which gates routine use of this procedure. The purge guard for flagged companies (D-143) belongs to slice 007.
- Memberships of the disabled account in companies it does not own stay `ACTIVE`, appear in member lists and keep using a plan seat (D-141, decided).
- Disabling does not clear `login_throttle` state; that is intended.

## Test record
Automated: `OwnerAccountDisableProcedureTests` and `OwnerAccountReversalProcedureTests` (run the script files against PostgreSQL in every build). `RunbookScriptGuardTest` keeps the `ON_ERROR_STOP` guard before the first statement of every writing script. On 2026-10-29 real `psql` 17.6 in a throwaway `postgres:17.6` container (migrations V1 to V15) showed both scripts quitting without changing anything when `ON_ERROR_STOP` is unset or 0, and running on to their first guard when it is 1. Manual rehearsal in a deployed environment: none yet. Record the date, the tester, and the result after the first rehearsal.
