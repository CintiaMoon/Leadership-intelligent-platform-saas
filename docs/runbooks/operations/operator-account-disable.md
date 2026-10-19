# Runbook: Operator account disable and session revocation

- Status: Draft (Phase 6, 2026-10-19). The SQL below was syntax-checked in a rolled-back transaction on a local PostgreSQL 17 database. It has **not been rehearsed** in a deployed environment and there is no operator API yet. Do not rely on it for a real incident without a rehearsal.
- Owner role: Security owner
- Related: SPEC-AUTH AC-AUTH-07, SPEC-MEM, SPEC-OWN, D-54

## What happens when an account is disabled
The session filter checks the account status on every request. A `DISABLED` account is rejected at once, even before its sessions are deleted, and cannot log in. Deleting the sessions then frees the rows. Company and membership effects (D-54) belong to later slices and are not covered here.

## Who may do it
The security owner approves; a database operator runs it with a named, audited role. Record the ticket and the reason.

## Procedure (single account)
Run in one transaction on the primary database, replacing the address:

```sql
BEGIN;
UPDATE app_user SET status = 'DISABLED' WHERE email_normalized = 'person@example.com' RETURNING id, status;
-- expect exactly one row
DELETE FROM spring_session
 WHERE principal_name IN (SELECT id::text FROM app_user WHERE email_normalized = 'person@example.com');
-- spring_session_attributes rows go with them (ON DELETE CASCADE)
COMMIT;
```

Then confirm: the user's next request returns 401, and a login attempt returns the same generic failure as a wrong password.

## Revoke sessions without disabling (suspected stolen session)
Run only the `DELETE FROM spring_session ...` statement. The user stays able to log in again. If the password may be known to the attacker, also require a password reset (the user can use the forgot-password page, which signs out every device).

## Re-enabling
`UPDATE app_user SET status = 'ACTIVE' WHERE email_normalized = '...';` Sessions are not restored; the user logs in again.

## Gaps to close
- No audit row is written by this manual procedure. Add an `audit_event` insert (event type, `actor_type = 'operator'`) or, better, an operator endpoint in the slice that introduces operators.
- Disabling does not clear `login_throttle` state; that is intended.

## Test record
None yet. Record the date, the tester, and the result after the first rehearsal.
