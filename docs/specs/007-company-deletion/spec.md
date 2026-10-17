# Company Deletion, Purge, and Tombstone Specification

- ID: SPEC-DEL
- Status: Draft
- Requirements: DEL-001 through DEL-006
- Note: decisions marked "Decided 2026-10-16" were approved by the user from the decision-register suggestions. The spec stays Draft until the product owner approves the full baseline.

## Objective
Support controlled company deletion, a grace period, permanent purge, and a minimal retained tombstone.

## States
ACTIVE -> PENDING_DELETION -> PURGE_QUEUED -> PURGING -> PURGED/TOMBSTONED
A cancellation by an Owner before the cutoff transitions PENDING_DELETION back to ACTIVE.
Proposed addition: a failed purge attempt transitions PURGING -> PURGE_FAILED (observable, per AC-DEL-05), and a retry transitions PURGE_FAILED -> PURGE_QUEUED.

## Workflow
This spec owns the deletion workflow (moved from SPEC-TEN).

Request deletion: an active Owner requests deletion. The backend verifies ownership, records the request and `deletion_scheduled_at`, and calculates `purge_at` as 30 days after the request, using a configured grace period (D-37, decided 2026-10-16). Requesting deletion cancels any pending ownership transfer and writes an audit event (D-31). The Owner and all Admins are notified by email through the outbox (D-53). Requesting deletion is rejected while the company is flagged for ownership resolution (SPEC-MEM, D-54).

Enter pending deletion: in a transaction, the company is set to PENDING_DELETION and the actor, timestamp, and reason (if collected) are recorded. The only permitted state change is an Owner's cancellation of the deletion. Every other state-changing operation is blocked, including business-record writes, settings changes, role changes, suspension and removal, invitation creation, acceptance, revocation and resend, members leaving, ownership transfers, and plan changes (D-38, decided 2026-10-16). Reads are limited to Owners.

Allow cancellation: only an active Owner may cancel before `purge_at`. The backend atomically checks the lifecycle state and deadline before restoring ACTIVE. Cancelling is audited, does not require recent authentication (D-37), and notifies the Owner and all Admins by email through the outbox (D-53). Deletion request, cancellation, and ownership-transfer completion all lock the company row first, then any transfer row (D-32).

Purge after the deadline: a background worker selects due companies, claims work safely, and performs an idempotent purge. Retries do not restore access or cause inconsistent partial deletion. The purge job is disabled by default, enabled per environment through configuration, and supports a dry-run mode; production enablement requires a reviewed runbook (D-42, decided 2026-10-16).

Retain a tombstone: only the company ID, the deletion-requested timestamp, and the purged timestamp are retained. Any further field requires legal and privacy review (D-39, decided 2026-10-16).

## Required behavior
- Only an authorized Owner can request deletion.
- State transitions are validated server-side.
- Purge jobs recheck current state and deadline.
- Purge is idempotent and retry-safe.
- Database records, files, external systems, and dependent records are handled according to a documented plan.
- Tombstones retain only the approved minimum metadata.
- Completion is verifiable.

## Open items
- Legal and financial records that must be retained.
- Audit retention (D-40). Backups are retained for 40 days, so purged data can persist in backups for up to 40 days after a purge; the legal and privacy position on this is open.
- External provider cleanup requirements; first inventory the external systems (D-41).

## Acceptance criteria
- AC-DEL-01: Unauthorized users cannot schedule deletion.
- AC-DEL-02: Purge cannot begin before `purge_at`, which is 30 days after the deletion request.
- AC-DEL-03: A stale job cannot purge an active/restored company.
- AC-DEL-04: Duplicate job execution is safe.
- AC-DEL-05: Partial failures are observable and retryable.
- AC-DEL-06: Only the company ID, the deletion-requested timestamp, and the purged timestamp remain in the tombstone.
- AC-DEL-07: After a purge, a verification step confirms that no company-owned database records remain other than the tombstone and audit events. External cleanup is verified once external systems are inventoried (D-41).
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

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| DEL-001 | AC-DEL-01, AC-DEL-09, AC-DEL-13, AC-DEL-15, AC-DEL-19, AC-DEL-20 |
| DEL-002 | AC-DEL-02 |
| DEL-003 | AC-DEL-03, AC-DEL-10, AC-DEL-17, AC-DEL-19 |
| DEL-004 | AC-DEL-04, AC-DEL-05, AC-DEL-16 |
| DEL-005 | AC-DEL-06 |
| DEL-006 | AC-DEL-07, AC-DEL-11 |
| Supporting | AC-DEL-08 (operational control), AC-DEL-12, AC-DEL-14, and AC-DEL-18 (pending-deletion restrictions) |

## Tests
Early execution, retries, duplicate jobs, cancellation races, concurrent deletion requests, partial failure, external cleanup failure, tombstone inspection, dry-run mode, and the purge-disabled default.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
