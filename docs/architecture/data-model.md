# Data Model — Spring Boot / PostgreSQL

## Status
Conceptual proposal. Review against approved requirements and the selected session implementation before creating migrations.

## Persistence rules
- PostgreSQL is authoritative.
- Spring Data JPA/Hibernate is the application persistence layer.
- Flyway SQL migrations are the production schema source of truth.
- Do not use Hibernate `ddl-auto=update` in production.
- Use DTOs at API boundaries; do not serialize JPA entities.
- Add explicit indexes, foreign keys, uniqueness, and check constraints where feasible.
- Prefer UTC `Instant`/timestamp semantics for expiries and lifecycle times; document any business-local date semantics.

## Conceptual entities
- `User`: stable ID, normalized unique email, password hash if password auth is approved, email verification state/time, account status, lockout metadata, timestamps, optimistic version where useful.
- `VerificationToken`: user reference, token hash, purpose, expiry, consumed/revoked timestamp, creation timestamp.
- `Company` (tenant): ID, display name, lifecycle state, trial/subscription references or fields, deletion requested time, purge eligibility time, timestamps, version.
- `Membership`: company ID, user ID, role (Owner/Admin/Agent/Viewer), state, timestamps. Unique active membership policy must be explicit.
- `Invitation`: company ID, normalized target email, role, token hash, expiry, consumed/revoked state, inviter ID, timestamps.
- `OwnershipTransfer`: company ID, current owner membership, target user/membership or verified target email, status, expiry, accepted/completed timestamps, audit references.
- `Plan`: stable code (Trial/Starter/Team), plan version/status, entitlement definitions.
- `CompanySubscription` or `CompanyPlan`: company ID, plan code/version, status, trial start/end, effective dates, optional billing provider references.
- `AuditEvent`: actor, tenant, action, target reference, timestamp, safe metadata; do not include raw secrets.
- `OutboxEvent` / durable job record: event type, safe payload, delivery status, attempts, scheduling fields, idempotency key.
- `CompanyTombstone`: minimal retained identifier, deletion/purge timestamps, retention reason/policy fields approved by product/legal review.

Spring Session JDBC may create framework-owned session tables; let the selected Spring Session version manage or document their schema according to its supported setup. Do not invent a custom session schema before deciding whether framework-managed tables meet operational needs.

## Required constraints and concurrency strategy
- Normalized email uniqueness must be enforced consistently, including case normalization.
- Invitation token hashes are unique; acceptance is single-use under concurrent requests.
- A user cannot gain membership merely by supplying a tenant ID.
- Ownership transfer is atomic; target acceptance is explicit; never leave a company without an Owner.
- The Owner cannot be removed, downgraded, or disabled through a conflicting operation; a company has exactly one Owner membership (suspended only while the company is flagged for ownership resolution, D-54). Enforce with a partial unique index on Owner memberships and a company-row lock on Owner-affecting writes (D-23, D-43). During a transfer, demote the previous Owner before promoting the recipient inside one transaction.
- At most one pending invitation per company and normalized email: partial unique index on pending invitations (D-25).
- At most one PENDING ownership transfer per company: partial unique index (D-32). Completion locks the company row before the transfer row.
- Membership status values: ACTIVE, SUSPENDED, REMOVED (proposed in SPEC-TEN).
- Trial and subscription state must be unambiguous and entitlement checks must be server-side.
- Deletion/purge operations must be idempotent and preserve the approved tombstone.

A service-level check alone is insufficient for race-prone invariants. Document and test the locking/conditional-update approach with PostgreSQL. Consider `@Version`, pessimistic row locks, unique constraints, or conditional SQL updates according to the invariant.

## Migration policy
- Each schema change uses a reviewed Flyway versioned migration.
- Avoid renaming/dropping columns in the same deployment that first stops using them if rolling deployment is possible; use expand/migrate/contract where needed.
- Review table locks, indexes, backfills, default values, and destructive operations.
- Test migrations on an empty database and, when relevant, on a representative prior-schema fixture.
