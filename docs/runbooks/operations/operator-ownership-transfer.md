# Runbook: Operator ownership transfer

- Status: Stub. Not yet written or tested. Do not rely on this for a real incident. The design below was decided on 2026-10-24 (D-142); the script, the test and the rehearsal belong to slice 005.
- Owner role: Security owner
- Related: SPEC-MEM, SPEC-OWN, D-32, D-54, D-141, D-142, D-143

## When it applies
A company is flagged for ownership resolution because an operator disabled its Owner's account (`operator-account-disable.md`) and the account will not simply return. If it may return, use the reversal script there instead. This runbook is the only way to change the Owner of a flagged company: the API refuses it (D-54).

## Decided design (D-142)
- **Two people.** The security owner approves the ticket; a database operator runs the script.
- **The ticket** holds the requester's identity, the evidence that they speak for the company, and the recipient's written consent. Verify the requester through a channel other than the disabled account's email. The security owner sets what counts as evidence.
- **The recipient** is an active Admin with an ACTIVE account and a verified email. If the company has no Admin, an active member that the security owner names in the ticket (promoted to Owner). Never a suspended or removed member, or an account that is not ACTIVE.
- **The old Owner** becomes Admin (as in a normal transfer, D-28) and stays `SUSPENDED` until the security owner decides otherwise, because the usual reason for a disable is a suspected compromise. After this the reversal script cannot restore the old Owner, by design.
- **One transaction.** Lock the company row first (D-32), then demote the old Owner and promote the recipient (the company has exactly one Owner membership at every committed point), clear `ownership_resolution_at`, cancel any pending ownership transfer (one PENDING per company), and write `ownership.transfer.operator_completed` with the ticket and the database role (and `company.ownership_resolution.cleared`).
- **Notifications** go through the outbox, outside the transaction: the new Owner and the company's Admins. Not the old Owner's address, which may be compromised.
- **Company with no other member:** nothing to transfer to. It stays flagged and retained (`sql/list-flagged-companies.sql`).
- **Deletion:** a company that is flagged must not be purged (D-143); the deadline is rechecked when the flag is cleared.

## Must still be done (slice 005)
- The script, with the same shape as the disable and reversal scripts (required `ticket`, normalized address, stops with an error on bad input, 5-second lock timeout), and a PostgreSQL test that runs it, with mutation checks on its safety conditions.
- The outbox event and email handler for the notifications.
- One rehearsal on staging with real `psql`, recorded below.

## Test record
None yet. Record the date, the tester, and the result after the first rehearsal.
