# Runbook: Operator ownership transfer

- Status: **Draft** (written 2026-10-26, slice 005 phase 4). The script is executed against PostgreSQL by `OperatorOwnershipTransferTests` on every build, and was run once with real `psql` on a copy of the development database (see "Test record"). It has **not been rehearsed on staging**; that rehearsal is the security owner's team's, and routine use of the account-disable procedure waits for it (D-142, plan Q-15).
- Owner role: Security owner
- Related: SPEC-MEM, SPEC-OWN (AC-OWN-18), D-32, D-54, D-141, D-142, D-143, `operator-account-disable.md`

## When it applies
A company is flagged for ownership resolution because an operator disabled its Owner's account (`operator-account-disable.md`) and the account will not simply return. If it may return, use the reversal script there instead. This runbook is the only way to change the Owner of a flagged company: the API refuses it (D-54).

List the flagged companies, oldest first, with the number of active members who could take over:

```
psql -f docs/runbooks/operations/sql/list-flagged-companies.sql
```

A company with `other_active_members = 0` has nobody to transfer to. It stays flagged and retained.

## Who does what (D-142)
- **The security owner** approves the ticket. **A database operator** runs the script with a named, audited database role. They are two different people.
- **The ticket** holds: the requester's identity, the evidence that they speak for the company, and the **recipient's written consent**. Verify the requester through a channel other than the disabled account's email. The security owner sets what counts as evidence.
- **The recipient** is an ACTIVE Admin of that company with an ACTIVE account and a verified email. Only if the company has no Admin may the security owner name an active Agent or Viewer in the ticket; the operator then passes `non_admin_ok=yes`. Never a suspended or removed member, a member of another company, or the old Owner.

## What the script does
One transaction, one company at a time (run it once per flagged company). The script:

1. **Checks the inputs** and stops with an error, committing nothing, if the ticket is malformed (letters, digits, `-`, `_`, up to 40), `non_admin_ok` is not exactly `yes` or `no`, or the company id is not a lower-case UUID.
2. **Locks** the company row first (the application's lock order, D-32, D-125), then the recipient's account row, so neither a concurrent change in the application nor a concurrent disable of the recipient can slip in between the checks and the commit. It gives up on a lock after 5 seconds; run it again.
3. **Checks the state** and stops with an error if any of these is false: the company is flagged; the address given as `email` is this company's Owner, whose membership is `SUSPENDED` and whose account is `DISABLED`; the recipient is an ACTIVE member of this company, not the Owner, an Admin (or an Agent or Viewer only with `non_admin_ok=yes`), with an ACTIVE account and a verified email.
4. **Changes**, in this order: cancels the company's pending ownership transfer, if any (resolution `cancelled_operator_transfer`; nobody is emailed about it); revokes the invitations the old Owner left pending; demotes the old Owner to Admin, **still `SUSPENDED`** (the usual reason for a disable is a suspected compromise, so the security owner decides later whether to reactivate; after this the reversal script cannot restore them, by design); promotes the recipient to Owner; clears `ownership_resolution_at`. The company has exactly one Owner membership at every committed point (the database enforces it).
5. **Audits** with `actor_type = 'operator'` and a reason holding the ticket (the completion event also holds the database role, because operators have no user ID yet): `ownership.transfer.cancelled`, `invitation.revoked`, `membership.role.changed` (twice), `company.ownership_resolution.cleared`, and `ownership.transfer.operator_completed`. No address or name is written.
6. **Queues notices** in the outbox, sent after the commit by the application: one to the new Owner and one to each ACTIVE Admin with an ACTIVE account and a verified email. The payload holds identifiers only. The old Owner is a suspended member now and is never written to; the sender checks the addressee again when it sends.
7. **Prints a check**: the flag is cleared, `owner_memberships = 1`, `pending_transfers = 0`.

It is not idempotent on purpose: a second run finds the flag cleared and stops with an error, changing nothing.

## Procedure
1. Get the ticket approved (see "Who does what"). Confirm the company id, the old Owner's address and the recipient's address from the ticket.
2. Take a backup point as for any production change (`restore-from-backup.md`), and look at the company first:
   ```
   psql -c "SELECT m.id, m.role, m.status, u.email_original, u.status AS account FROM membership m JOIN app_user u ON u.id = m.user_id WHERE m.company_id = '<company id>' ORDER BY m.role, m.created_at"
   ```
3. Run the script in **one transaction**. All five variables are required; a missing one makes the statement fail and nothing is committed.
   ```
   psql --single-transaction --set ON_ERROR_STOP=1 \
        -v email=old.owner@example.com -v recipient=new.owner@example.com \
        -v company=<company uuid> -v ticket=SEC-125 -v non_admin_ok=no \
        -f docs/runbooks/operations/sql/operator-ownership-transfer.sql
   ```
4. Read the last query's output. `ownership_resolution_at` must be empty, `owner_memberships` 1 and `pending_transfers` 0. Anything else, or any error: do not commit (`ON_ERROR_STOP` and `--single-transaction` roll back on an error; if you ran it interactively, `ROLLBACK`).
5. Confirm after the commit: the new Owner can sign in and open the company and its members page; the Members page shows the old Owner as a suspended Admin; the notices were sent (the `outbox_event` rows of type `OWNERSHIP_TRANSFER_OPERATOR_COMPLETED` whose payload names this company reach `SENT`; one that stays `FAILED` was not delivered and needs a look at the mail configuration).
6. Record the ticket, the date and the result. Tell the requester through the channel in the ticket.

## If it goes wrong
- **An error from the script:** nothing was changed. Read the error: a division by zero names the failed condition in the column alias of the query (`company_is_flagged`, `owner_is_suspended_and_disabled`, `recipient_is_eligible`, `ticket_ok`, ...). Fix the input or the ticket, never the script.
- **A lock timeout:** a long application transaction holds the company. Wait a minute and run it again.
- **A wrong recipient, found after the commit:** the new Owner can transfer ownership again through the application (Ownership page), or open a new ticket. Do not edit memberships by hand.
- **The old Owner's account should come back:** after this script the reversal script cannot restore the old Owner. Their membership is a suspended Admin and their account is still disabled; the security owner decides, through a new ticket, whether to enable the account again and whether the new Owner reactivates the membership through the application (the Members page). Do not edit memberships by hand.

## Gaps to close
- There is no operator endpoint or operator identity; the audit rows carry the ticket and the database role (see `operator-account-disable.md`).
- The deletion guard for flagged companies (D-143) belongs to slice 007; the deadline is rechecked when the flag is cleared.
- The cancelled pending transfer sends no email: its initiator is the suspended Owner and its recipient learns of the new Owner from the notice only if they are an Admin or the new Owner.
- Not tested: a lock timeout of exactly 5 seconds (the two lock-wait tests prove the order of the locks, not the timeout value).

## Test record
Automated: `OperatorOwnershipTransferTests` (19 tests against PostgreSQL in every build; its mutation checks are listed in `docs/specs/005-ownership-transfer/traceability.md`).

Manual: see the table below. The staging rehearsal that D-142 asks for has **not** been done; record its date, the tester and the result here.

| Date | Environment | Tester | Result |
|---|---|---|---|
| 2026-10-26 | local `psql` 17 in the dev Postgres container, throwaway copy of the development database | Claude (assistant) | Passed: bad ticket and missing variable stop the script; the transfer ends with one Owner, flag cleared, nothing pending, 6 audit rows, 2 outbox rows; a second run stops. Notices not sent (no application running). Details in the SPEC-OWN traceability |
| (pending) | staging | security owner's team | not done |
