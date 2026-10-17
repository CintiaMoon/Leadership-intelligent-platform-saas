# Invitations Specification

- ID: SPEC-INV
- Status: Draft
- Requirements: INV-001 through INV-005
- Note: decisions marked "Decided 2026-10-16" were approved by the user from the decision-register suggestions. The spec stays Draft until the product owner approves the full baseline.

## Objective
Allow authorized company members to invite users through a secure, time-limited, single-use process.

## Behavior
- The inviter must have permission to invite the requested role.
- The invitation is bound to a normalized email and company.
- Generate a cryptographically random token and store only a secure hash.
- Acceptance requires an authenticated user whose verified email matches the invitation.
- Token consumption and membership creation are atomic.
- Delivery uses a durable mechanism (database outbox, ADR-003).

## Decisions (Decided 2026-10-16)
- Expiry: an invitation expires 7 days after it is issued (D-24).
- Pending invitations: at most one pending invitation exists per company and email address, enforced by a partial unique index. Resending rotates the token and invalidates the previous one. Revoking takes effect immediately (D-25).
- Existing members and deletion: creating an invitation for an email that already has an ACTIVE membership in the company is rejected (D-26). Creating or accepting an invitation for a company in PENDING_DELETION is rejected (SPEC-TEN).
- Role permissions: an Owner may invite Admin, Agent, or Viewer; an Admin may invite Agent or Viewer; nobody can invite an Owner; Agent and Viewer cannot invite (D-27, derived from the SPEC-MEM role-assignment table).
- Token handling: the invitation link carries the raw token in the URL fragment; the client sends it in the request body; the token never appears in a request path or query string (D-44).
- Abuse control: failed invitation-token attempts are rate limited per IP address (limit in AC-INV-11).
- Outbound invitation emails are limited to 20 per inviter per hour, 50 per company per day, and 3 sends per recipient address per 24 hours, with resends counting (D-52, decided 2026-10-16).
- Member limit: accepting an invitation is rejected when the company's active-member limit for its plan has been reached (SPEC-PLAN).

## Acceptance criteria
- AC-INV-01: Unauthorized users cannot issue invitations.
- AC-INV-02: Acceptance requires the matching verified email.
- AC-INV-03: Expired, revoked, consumed, or invalid invitations are rejected.
- AC-INV-04: Concurrent acceptance creates at most one membership.
- AC-INV-05: Token hashes, not raw tokens, are persisted.
- AC-INV-06: Invitation acceptance cannot cross company boundaries.
- AC-INV-07: Raw tokens are not logged.
- AC-INV-08: Acceptance by an unauthenticated caller, or by a user whose email is not verified, is rejected and does not consume the invitation.
- AC-INV-09: Acceptance for a company that does not exist, has been purged, or is in PENDING_DELETION is rejected and creates no membership (SPEC-TEN: pending deletion blocks new memberships).
- AC-INV-10: Accepting an invitation as a user who already has an active membership in that company creates no additional membership.
- AC-INV-11: The 11th failed invitation-token attempt from one IP address within an hour is throttled.
- AC-INV-12: Accepting an invitation when the company's active-member limit for its current plan has been reached is rejected and creates no membership, including under concurrent acceptances (PLAN-003, PLAN-005; SPEC-PLAN).
- AC-INV-13: Creating a new invitation for a company in PENDING_DELETION is rejected (SPEC-TEN).
- AC-INV-14: An invitation is rejected for acceptance 7 days after it was issued.
- AC-INV-15: At most one pending invitation exists per company and email, including under concurrent requests; resending rotates the token and the previous token is rejected.
- AC-INV-16: Creating an invitation for an email that already has an ACTIVE membership in the company is rejected.
- AC-INV-17: An actor can invite only the permitted roles: Owner may invite Admin, Agent, or Viewer; Admin may invite Agent or Viewer; no actor can invite an Owner; Agent and Viewer cannot invite.
- AC-INV-18: The accept operation receives the token in the request body; a token supplied in the request path or query string is not accepted.
- AC-INV-19: Invitation tokens have at least 128 bits of entropy and are looked up by hash.
- AC-INV-20: The invitation accept page sends `Referrer-Policy: no-referrer` and removes the token fragment from the address bar after reading it.
- AC-INV-21: Resending or revoking an invitation updates the invitation and rotates or invalidates its token in a single transaction.
- AC-INV-22: Accepting with a token whose invitation email does not match the signed-in user returns the same error code as an invalid token.
- AC-INV-23: The 21st invitation by one inviter within an hour, the 51st invitation for one company within a day, and the 4th send to one recipient address within 24 hours are rejected.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| INV-001 | AC-INV-01, AC-INV-16, AC-INV-17 |
| INV-002 | AC-INV-03, AC-INV-05, AC-INV-14, AC-INV-15, AC-INV-19, AC-INV-21 |
| INV-003 | AC-INV-02, AC-INV-08, AC-INV-22 |
| INV-004 | AC-INV-03, AC-INV-09 |
| INV-005 | AC-INV-04, AC-INV-10 |
| Supporting | AC-INV-06 (tenant boundary), AC-INV-07, AC-INV-18, and AC-INV-20 (NFR-SEC-003, token handling), AC-INV-11 and AC-INV-23 (abuse control), AC-INV-12 (PLAN-003, PLAN-005 member quota), AC-INV-13 (pending-deletion block) |

## Tests
Test matching/mismatching email, expiry at 7 days, replay, revocation, resend token rotation, concurrent acceptance, concurrent invitation creation, inactive company, inviter permissions per role, existing members, and cross-tenant manipulation.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
