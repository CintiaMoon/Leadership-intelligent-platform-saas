# Invitations Specification

- ID: SPEC-INV
- Status: Approved
- Approved on 2026-10-24 with the amendments listed below. Approver: product owner (the user: "approved spec amendments").
- Requirements: INV-001 through INV-005
- Note: decisions marked "Decided 2026-10-16" were approved by the user from the decision-register suggestions.
- Amended on 2026-10-24 (plan `plan.md`, section 3): expiry counts from the latest create or resend; creating or resending an invitation for Admin needs recent authentication (AC-INV-27); a SUSPENDED member counts as an existing member (AC-INV-16); a second create for the same company and address is rejected while one is pending (AC-INV-28); accepting as an existing member changes nothing (AC-INV-10, AC-INV-29). No earlier decision was reversed.

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
- Expiry: an invitation expires 7 days after it was issued; it is issued when it is created and again each time it is resent, so a resend starts a new 7 days (D-24, amended 2026-10-24).
- Pending invitations: at most one pending invitation exists per company and email address, enforced by a partial unique index. Resending rotates the token and invalidates the previous one. Revoking takes effect immediately (D-25).
- Existing members and deletion: creating an invitation for an email that already has an ACTIVE or SUSPENDED membership in the company is rejected (D-26, amended 2026-10-24: a suspended member is reactivated, not re-invited, and an invitation must never get around a suspension). A REMOVED member can be invited again; the new membership is a new row and the history is kept. Creating or accepting an invitation for a company in PENDING_DELETION is rejected (SPEC-TEN).
- Duplicates and expired invitations: creating an invitation for a company and address that already has a pending, unexpired invitation is rejected (use resend). An expired pending invitation does not block a new one (amended 2026-10-24).
- Accepting as an existing member: if the signed-in user already has an ACTIVE or SUSPENDED membership in the company, acceptance is rejected, changes no membership or role, and does not consume the invitation (amended 2026-10-24).
- Inviting an Admin: creating or resending an invitation for the Admin role requires that the session's last login or step-up was within the previous 10 minutes, like promoting a member to Admin (SPEC-AUTH AC-AUTH-14, amended 2026-10-24).
- Role permissions: an Owner may invite Admin, Agent, or Viewer; an Admin may invite Agent or Viewer; nobody can invite an Owner; Agent and Viewer cannot invite (D-27, derived from the SPEC-MEM role-assignment table).
- Token handling: the invitation link carries the raw token in the URL fragment; the client sends it in the request body; the token never appears in a request path or query string (D-44).
- Abuse control: failed invitation-token attempts are rate limited per IP address (limit in AC-INV-11).
- Outbound invitation emails are limited to 20 per inviter per hour, 50 per company per day, and, for one recipient address, 3 sends per company and 10 sends across all companies per 24 hours, with resends counting (D-52, decided 2026-10-16; the recipient limits were changed to per company plus a global ceiling on 2026-10-25, D-164). A request that is refused before an email is queued (already a member, already pending, invalid) does not use up a recipient allowance.
- Member limit: accepting an invitation is rejected when the company's active-member limit for its plan has been reached (SPEC-PLAN). Creating an invitation is not capped by remaining plan capacity; the interface shows a warning (decided 2026-10-17).
- Inviter changes: when an inviter's membership is removed or suspended, or their role changes so they can no longer grant the invited role, their pending invitations for roles they can no longer grant are revoked automatically (D-62, decided 2026-10-17).
- New users: an invited person without an account signs up and verifies their email through the normal flow, then accepts. Following an invitation link does not mark an email as verified (D-63, decided 2026-10-17).

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
- AC-INV-10: Accepting an invitation as a user who already has an active or suspended membership in that company creates no additional membership, changes no role, and is rejected.
- AC-INV-11: The 11th failed invitation-token attempt from one IP address within an hour is throttled.
- AC-INV-12: Accepting an invitation when the company's active-member limit for its current plan has been reached is rejected and creates no membership, including under concurrent acceptances (PLAN-003, PLAN-005; SPEC-PLAN).
- AC-INV-13: Creating a new invitation for a company in PENDING_DELETION is rejected (SPEC-TEN).
- AC-INV-14: An invitation is rejected for acceptance 7 days after it was issued, where a resend issues it again.
- AC-INV-15: At most one pending invitation exists per company and email, including under concurrent requests; resending rotates the token and the previous token is rejected.
- AC-INV-16: Creating an invitation for an email that already has an ACTIVE or SUSPENDED membership in the company is rejected; a REMOVED member can be invited again.
- AC-INV-17: An actor can invite only the permitted roles: Owner may invite Admin, Agent, or Viewer; Admin may invite Agent or Viewer; no actor can invite an Owner; Agent and Viewer cannot invite.
- AC-INV-18: The accept operation receives the token in the request body; a token supplied in the request path or query string is not accepted.
- AC-INV-19: Invitation tokens have at least 128 bits of entropy and are looked up by hash.
- AC-INV-20: The invitation accept page sends `Referrer-Policy: no-referrer` and removes the token fragment from the address bar after reading it.
- AC-INV-21: Resending or revoking an invitation updates the invitation and rotates or invalidates its token in a single transaction.
- AC-INV-22: Accepting with a token whose invitation email does not match the signed-in user returns the same error code as an invalid token.
- AC-INV-24: When an inviter is removed or suspended, or loses the ability to grant an invited role, their pending invitations for roles they can no longer grant are revoked and can no longer be accepted.
- AC-INV-25: Following an invitation link does not mark the invitee's email as verified; acceptance still requires a verified email matching the invitation.
- AC-INV-26: Creating an invitation is not rejected because of the company's remaining plan capacity; acceptance is rejected at the limit (AC-INV-12).
- AC-INV-23: The 21st invitation by one inviter within an hour, the 51st invitation for one company within a day, and the 4th send to one recipient address within 24 hours are rejected.
- AC-INV-27: Creating or resending an invitation for the Admin role is rejected unless the session's last login or step-up was within the previous 10 minutes; other roles do not need it.
- AC-INV-28: Creating an invitation for a company and address that already has a pending, unexpired invitation is rejected; after that invitation has expired (or been revoked) a new one can be created, and at most one pending invitation exists per company and address.
- AC-INV-29: Accepting an invitation as a user who already has an active or suspended membership in the company leaves the invitation pending and changes nothing (AC-INV-10).

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| INV-001 | AC-INV-01, AC-INV-16, AC-INV-17, AC-INV-27, AC-INV-28 |
| INV-002 | AC-INV-03, AC-INV-05, AC-INV-14, AC-INV-15, AC-INV-19, AC-INV-21 |
| INV-003 | AC-INV-02, AC-INV-08, AC-INV-22, AC-INV-25 |
| INV-004 | AC-INV-03, AC-INV-09, AC-INV-24 |
| INV-005 | AC-INV-04, AC-INV-10, AC-INV-29 |
| Supporting | AC-INV-06 (tenant boundary), AC-INV-07, AC-INV-18, and AC-INV-20 (NFR-SEC-003, token handling), AC-INV-11 and AC-INV-23 (abuse control), AC-INV-12 and AC-INV-26 (PLAN-003, PLAN-005 member quota), AC-INV-13 (pending-deletion block) |

## Tests
Test matching/mismatching email, expiry at 7 days, replay, revocation, resend token rotation, concurrent acceptance, concurrent invitation creation, inactive company, inviter permissions per role, existing members, and cross-tenant manipulation.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
