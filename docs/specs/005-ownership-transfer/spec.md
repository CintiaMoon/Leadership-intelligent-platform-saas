# Ownership Transfer Specification

- ID: SPEC-OWN
- Status: Draft
- Requirements: OWN-001 through OWN-005
- Note: decisions marked "Decided 2026-10-16" were approved by the user from the decision-register suggestions. The spec stays Draft until the product owner approves the full baseline.

## Objective
Transfer company ownership only after explicit acceptance by an eligible recipient, without races that violate company invariants.

## State machine
PENDING -> COMPLETED | CANCELLED | EXPIRED

A transfer expires 7 days after it is initiated. The initiator can cancel a pending transfer.

## Completion transaction
1. Verify the transfer is pending and unexpired.
2. Lock the company row first, then the transfer row. Under the lock, require the company to be ACTIVE (not PENDING_DELETION) and the transfer's initiator to still be the active Owner.
3. Verify recipient eligibility and explicit acceptance.
4. Verify, under the lock, that the recipient is still an existing active member of the company.
5. Demote the previous Owner to Admin, then promote the recipient to Owner, so the company has exactly one active Owner at every committed point.
6. Mark the transfer completed and write an audit/outbox event.
7. Commit only if every invariant holds.

Do not call email or other external providers inside the database transaction.

## Decisions (Decided 2026-10-16)
- The recipient must be an existing active member of the company (D-29).
- The previous Owner becomes Admin (D-28).
- A transfer expires after 7 days; the initiator can cancel (D-30).
- Deletion and transfer: initiating a transfer while the company is PENDING_DELETION is rejected, and requesting deletion cancels a pending transfer with an audit entry (D-31).
- At most one PENDING transfer exists per company, enforced by a partial unique index; completion locks the company row before the transfer row (D-32).
- Recent authentication (within 10 minutes) is required for both the initiator and the recipient (SPEC-AUTH, D-15).
- A company has exactly one Owner (D-43).
- When an operator has disabled the Owner's account, the Owner can be changed only by an audited, operator-assisted transfer to an active member through a runbook, not through this API (D-54).

## Acceptance criteria
- AC-OWN-01: Only an eligible Owner can initiate transfer.
- AC-OWN-02: Recipient acceptance is explicit and authenticated.
- AC-OWN-03: Repeated or expired acceptance cannot complete a transfer.
- AC-OWN-04: Concurrent transfers cannot create inconsistent ownership.
- AC-OWN-05: Exactly one Owner membership exists after every committed transition, and it is active except while the company is flagged for ownership resolution (SPEC-MEM, D-54).
- AC-OWN-06: Completion is audited and notification is retry-safe.
- AC-OWN-07: The initiator can cancel a pending transfer, and a cancelled transfer cannot be accepted.
- AC-OWN-08: Initiating a transfer to a user who is not an active member of the company is rejected, and accepting a transfer after the recipient's membership has ceased to be active is rejected.
- AC-OWN-09: Initiating a transfer while the company is PENDING_DELETION is rejected; requesting deletion cancels a pending transfer and writes an audit event.
- AC-OWN-10: At most one PENDING ownership transfer exists per company; initiating a second while one is pending is rejected, including under concurrent requests.
- AC-OWN-11: Initiating an ownership transfer is rejected unless the initiator's last authentication was within the previous 10 minutes (SPEC-AUTH).
- AC-OWN-12: Accepting an ownership transfer is rejected unless the recipient's last authentication was within the previous 10 minutes (SPEC-AUTH).
- AC-OWN-13: On completion the recipient is the Owner and the previous Owner is an Admin.
- AC-OWN-14: A transfer pending for more than 7 days is EXPIRED and cannot be accepted.
- AC-OWN-15: Completing a transfer is rejected if, at completion time, the company is not ACTIVE or the transfer's initiator is no longer the active Owner.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| OWN-001 | AC-OWN-01, AC-OWN-08, AC-OWN-09, AC-OWN-11 |
| OWN-002 | AC-OWN-02, AC-OWN-12 |
| OWN-003 | AC-OWN-04, AC-OWN-05, AC-OWN-10, AC-OWN-13, AC-OWN-15 |
| OWN-004 | AC-OWN-03, AC-OWN-07, AC-OWN-14 |
| OWN-005 | AC-OWN-05, AC-OWN-06 |

## Tests
Concurrent acceptance, competing transfers, expiry at 7 days, initiator cancellation, recipient ineligibility, pending deletion in both directions, and database rollback after an injected failure.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
