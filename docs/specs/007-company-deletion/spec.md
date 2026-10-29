# Company Deletion, Purge, and Tombstone Specification

- ID: SPEC-DEL
- Status: Approved 2026-10-29 with the amendments marked "2026-10-29" below and AC-DEL-25 to AC-DEL-30 (plan Q-03, Q-05, Q-07, Q-10, Q-13, Q-15, Q-26 to Q-33). D-40, D-41 and D-61 stay Proposed (working basis, plan Q-02).
- Requirements: DEL-001 through DEL-006
- Note: decisions marked "Decided 2026-10-16" were approved by the user from the decision-register suggestions. The product owner approved the spec with the 2026-10-29 amendments when approving the implementation plan.

## Objective
Support controlled company deletion, a grace period, permanent purge, and a minimal retained tombstone.

## States
ACTIVE -> PENDING_DELETION -> PURGE_QUEUED -> PURGING -> PURGED/TOMBSTONED
PURGED/TOMBSTONED means that the company row is gone and a tombstone exists (2026-10-29, plan Q-03). PURGE_QUEUED, PURGING and PURGE_FAILED are stored company states.
A cancellation by an Owner before the cutoff transitions PENDING_DELETION back to ACTIVE.
A failed purge attempt transitions PURGING -> PURGE_FAILED (observable, per AC-DEL-05), and a retry transitions PURGE_FAILED -> PURGE_QUEUED. Retries use exponential backoff up to 5 attempts; after the fifth failure the company stays PURGE_FAILED, an alert is raised (a stable error log line, a metric and an alert condition for a deadline missed; paging belongs to the deployment slice), and an operator resumes it through a runbook (D-60, decided 2026-10-17; plan Q-13, Q-25).
Later states (2026-10-29, plan Q-07): in PURGE_QUEUED, PURGING and PURGE_FAILED every operation is rejected for every role; an Owner can still read the company's lifecycle status. The cancellation is rejected from `purge_at` onward even before the worker queues the purge.

## Workflow
This spec owns the deletion workflow (moved from SPEC-TEN).

Request deletion: an active Owner requests deletion. The backend verifies ownership, records the request and `deletion_scheduled_at`, and calculates `purge_at` as 30 days after the request, using a configured grace period (D-37, decided 2026-10-16). Requesting deletion cancels any pending ownership transfer and writes an audit event (D-31). The Owner and all Admins are notified by email through the outbox (D-53). Requesting deletion is rejected while the company is flagged for ownership resolution (SPEC-MEM, D-54). No reason is collected (2026-10-29, plan Q-05). Every other active member receives a short notice at request time, and the Owner and the Admins receive reminders 7 days and 1 day before `purge_at` unless the deletion was cancelled (plan Q-27). Request and cancel are rate-limited, and at most one deletion email wave per company is sent per hour (plan Q-26).

Enter pending deletion: in a transaction, the company is set to PENDING_DELETION and the actor, timestamp, and reason (if collected) are recorded. The only permitted state change is an Owner's cancellation of the deletion. Every other state-changing operation is blocked, including business-record writes, settings changes, role changes, suspension and removal, invitation creation, acceptance, revocation and resend, members leaving, ownership transfers, and plan changes (D-38, decided 2026-10-16). Reads are limited to Owners.

Allow cancellation: only an active Owner may cancel before `purge_at`. The backend atomically checks the lifecycle state and deadline before restoring ACTIVE. Cancelling is audited, does not require recent authentication (D-37), and notifies the Owner and all Admins by email through the outbox (D-53). Deletion request, cancellation, and ownership-transfer completion all lock the company row first, then any transfer row (D-32).

Purge after the deadline: a background worker selects due companies, claims work safely, and performs an idempotent purge. Retries do not restore access or cause inconsistent partial deletion. The purge job is disabled by default, enabled per environment through configuration, and supports a dry-run mode; production enablement requires a reviewed runbook (D-42, decided 2026-10-16). The worker has limits per run and per hour, an allowlist of company IDs for the first runs, a circuit breaker when too many companies are due at once, and skips a company under an operator hold (2026-10-29, plan Q-31, Q-33).

Purge manifest: each module registers a purge handler for its company-owned tables, and a CI check fails if any table with a `company_id` column has no handler (D-57, decided 2026-10-17). `outbox_event` gets a nullable `company_id` so that its rows are attributable (2026-10-29, plan Q-10).

Effects of a purge: the company's memberships, invitations, ownership transfers, subscription and usage data, outbox records, and company data are deleted. User accounts are kept because they are global and may belong to other companies. Audit events are kept for the retention period (D-58, decided 2026-10-17; retention is D-40).

Retain a tombstone: only the company ID, the deletion-requested timestamp, and the purged timestamp are retained. Any further field requires legal and privacy review (D-39). The tombstone serves audit evidence, support lookup, and the deletion ledger. Company IDs are never reused, so it does not need to prevent recreation.

Deletion ledger: after any backup restore, the tombstones are replayed to re-purge every deleted company, so purged data does not reappear (D-59, decided 2026-10-17). The replay is an operator-run command, not a scheduled job (2026-10-29, plan Q-15).

## Required behavior
- Only an authorized Owner can request deletion.
- State transitions are validated server-side.
- Purge jobs recheck current state and deadline.
- Purge is idempotent and retry-safe.
- Database records, files, external systems, and dependent records are handled according to a documented plan.
- Tombstones retain only the approved minimum metadata.
- Completion is verifiable.

## Open items
- Legal and financial records to retain: none for the MVP because billing is out of scope; revisit with the billing slice. Legal confirmation is pending (D-61).
- Audit retention is proposed at 12 months, after which audit events are deleted (D-40). Backups are retained for 40 days, so purged data can persist in backups for up to 40 days after a purge; the privacy documentation must state this. Legal review is pending.
- External systems (D-41, proposed, verification pending): Amazon SES stores no message bodies and its suppression entries are kept for deliverability; CloudWatch Logs hold no tenant content and have a fixed retention; backups expire in 40 days. No per-company cleanup is expected, which must be verified.

## Acceptance criteria
- AC-DEL-01: Unauthorized users cannot schedule deletion.
- AC-DEL-02: Purge cannot begin before `purge_at`, which is 30 days after the deletion request.
- AC-DEL-03: A stale job cannot purge an active/restored company.
- AC-DEL-04: Duplicate job execution is safe.
- AC-DEL-05: Partial failures are observable and retryable.
- AC-DEL-06: Only the company ID, the deletion-requested timestamp, and the purged timestamp remain in the tombstone.
- AC-DEL-07: After a purge, a verification query finds no rows carrying the company's ID in any table registered in the purge manifest, other than the tombstone and audit events.
- AC-DEL-08: The purge job does not run in an environment unless its purge-enabled configuration is set; the default is disabled.
- AC-DEL-09: Concurrent deletion requests for the same company produce exactly one transition to PENDING_DELETION.
- AC-DEL-10: Only an active Owner can cancel a pending deletion. Cancellation before the purge cutoff restores ACTIVE; cancellation after the cutoff is rejected; the lifecycle state and deadline are checked atomically (SPEC-TEN).
- AC-DEL-11: Purge completion writes an audit event that contains no purged company content.
- AC-DEL-12: While a company is PENDING_DELETION, every state-changing operation is rejected except an Owner's cancellation of the deletion (AC-DEL-10). This includes business-record writes, settings changes, role changes, suspension and removal, invitation creation, acceptance, revocation and resend, members leaving, ownership transfers, and plan changes.
- AC-DEL-13: A company deletion request is rejected unless the requester's last authentication was within the previous 10 minutes (SPEC-AUTH).
- AC-DEL-14: While a company is PENDING_DELETION, only an Owner can read company data; reads by other members are rejected (SPEC-TEN).
- AC-DEL-15: Requesting deletion cancels any pending ownership transfer and writes an audit event.
- AC-DEL-16: In dry-run mode the purge job reports the records it would delete and deletes nothing.
- AC-DEL-17: Cancelling a pending deletion writes an audit event and does not require recent authentication.
- AC-DEL-18: Every state-changing row of the SPEC-TEN permission matrix is rejected for a company in PENDING_DELETION, except the Owner's cancellation, verified by one test per row.
- AC-DEL-19: Requesting a deletion and cancelling a pending deletion each enqueue an email notification to the Owner and all Admins.
- AC-DEL-20: Requesting deletion is rejected while the company is flagged for ownership resolution.
- AC-DEL-21: A CI check fails if a table with a `company_id` column has no registered purge handler.
- AC-DEL-22: After a purge, the company's memberships, invitations, ownership transfers, and outbox records are deleted, while user accounts and audit events remain.
- AC-DEL-23: After a backup restore, replaying the deletion ledger re-purges every company that has a tombstone.
- AC-DEL-24: A failed purge attempt is retried with exponential backoff up to 5 attempts; after the fifth failure the company remains PURGE_FAILED and an alert is raised.
- AC-DEL-25: Every other active member receives a short notice when a deletion is requested; the Owner and the Admins receive reminders 7 days and 1 day before `purge_at`; none is sent after a cancellation; each is queued once.
- AC-DEL-26: The purge worker never purges more than the configured number per run and per hour, purges only allowlisted companies when an allowlist is set, and does nothing and raises an alert when more than the circuit-breaker number are due at once.
- AC-DEL-27: A company under an operator purge hold is not purged; setting and clearing the hold is audited with its ticket; clearing it makes the company due again.
- AC-DEL-28: Requesting and cancelling a deletion are rate-limited per company and per user; at most one deletion email wave per company is sent per hour; every request and cancellation is audited even when its email is held back.
- AC-DEL-29: In PURGE_QUEUED, PURGING and PURGE_FAILED every operation is rejected for every role; an Owner can read the lifecycle status; a cancellation is rejected from `purge_at` onward.
- AC-DEL-30: The audit events written by this slice contain only identifiers, enumerated values and tickets: no email address, name or free text.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| DEL-001 | AC-DEL-01, AC-DEL-09, AC-DEL-13, AC-DEL-15, AC-DEL-19, AC-DEL-20 |
| DEL-002 | AC-DEL-02 |
| DEL-003 | AC-DEL-03, AC-DEL-10, AC-DEL-17, AC-DEL-19 |
| DEL-004 | AC-DEL-04, AC-DEL-05, AC-DEL-16, AC-DEL-24 |
| DEL-005 | AC-DEL-06 |
| DEL-006 | AC-DEL-07, AC-DEL-11, AC-DEL-22 |
| Supporting | AC-DEL-08 (operational control), AC-DEL-12, AC-DEL-14, and AC-DEL-18 (pending-deletion restrictions), AC-DEL-21 and AC-DEL-23 (purge manifest and restore ledger), AC-DEL-25 to AC-DEL-30 (notices and reminders, purge limits, hold, abuse limits, later states, audit content) |

## Tests
Early execution, retries, duplicate jobs, cancellation races, concurrent deletion requests, partial failure, external cleanup failure, tombstone inspection, dry-run mode, and the purge-disabled default.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
