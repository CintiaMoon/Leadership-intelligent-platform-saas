# Implementation Plan: SPEC-MEM (slice 003)

- Status: **Approved 2026-10-21** (user: "Approve Q-01 to Q-18", the phase order approved, the D-121 window of 1 hour confirmed). Q-NN is recorded as D-(122+NN) in the register (D-123 to D-140). Phase 0 is this change; nothing else is implemented.
- Spec: `docs/specs/003-memberships/spec.md` (SPEC-MEM, Approved 2026-10-17)
- Related: SPEC-TEN (`docs/specs/002-tenants/spec.md`, the permission matrix), SPEC-AUTH (AC-AUTH-14, 22, 23), `docs/architecture/invariants.md`, `data-model.md`, `audit-events.md`, ADR-005; decisions D-18, D-20, D-21, D-22, D-23, D-43, D-54, D-89, D-108 to D-121
- Date: 2026-10-21. Written from the spec, the register and the slice 001 and 002 code; no code was changed.

## 1. Readiness check (CLAUDE.md workflow step 1)
| Item | State | Action |
|---|---|---|
| D-18 (matrix), D-20, D-21, D-22, D-23, D-43, D-54, D-89 | Decided; none of them is Open | None |
| D-32 (lock order: company row, then transfer row) | Decided; belongs to slice 005 | 003 uses the same order: company row first |
| D-121 (denial-audit window) | Approved and implemented, but the 1-hour window is unconfirmed | Not blocking; 003 adds more routes that can deny, so confirm the window before Phase 3 |
| D-68 (core domain spec) | Open; blocks 006 and 007 only | None |
| D-40 (audit retention) | Partly decided; legal review pending | Not blocking. The new audit columns (Q-07) hold only role and status values |
| Slice 002 closure | Not yet recorded (reviews done, D-121 and D-122 merged) | Phase 0 records it |
| Plan limits (slice 006) | Not built | AC-MEM-17 cannot be verified in 003; see Q-12 |
| Invitations, ownership transfer (slices 004, 005) | Not built | Members enter only through invitations, so 003 has no "add member" endpoint |

## 2. Assumptions and trust boundaries
- Browser untrusted; the BFF authenticates to the API; the API is the only authority. `companyId` and `membershipId` in a path are selectors, never proof of access (D-03).
- The actor's identity comes from the session. The actor's role and the target's role and status are loaded from the database **inside the transaction that changes them**, after the locks (Q-03). The role-check-then-write gap noted in the slice 002 reviews is closed here, not carried forward.
- A membership ID is looked up only together with the company ID from the authorized context (`findByCompanyIdAndId`), so another company's membership looks like a missing one.
- The Owner is never changed through these endpoints. Ownership moves only through slice 005 and, for a disabled Owner account, through the operator runbook (D-54).

## 3. Gaps found, with proposed answers
| ID | Gap | Proposal |
|---|---|---|
| Q-01 | The spec defines no endpoints (examples are not approved until in OpenAPI) | Under `/api/v1/companies/{companyId}`: `GET /members` (member list); `PATCH /members/{membershipId}` with body `{role}`; `POST /members/{membershipId}/suspend`; `POST /members/{membershipId}/reactivate`; `DELETE /members/{membershipId}` (remove); `POST /leave` (the caller leaves). Added to `contracts/openapi.yaml` before the code. Actions that carry no data are bodiless POSTs, so CSRF applies as for every state change |
| Q-02 | The target-dependent cells ("Agent/Viewer only") cannot be decided by the `@Authorized` argument resolver, which has no target | The controllers do not use `@Authorized` for these routes. They pass the company ID, the membership ID and the intent to an application service that authorizes inside its own transaction (Q-03). Role checks stay out of controllers; the matrix stays in `CompanyPolicy` |
| Q-03 | Check-then-write races (TOCTOU), AC-MEM-03, 08, 16 | One `@Transactional` method per operation: (1) `SELECT ... FOR UPDATE` on the company row, (2) the target membership `FOR UPDATE`, (3) re-read the actor's membership and require ACTIVE, (4) authorize with `CompanyPolicy` using the locked roles, (5) check the transition, (6) write, (7) audit. The company-row lock serializes all membership changes in a company, which is simple and deadlock-free; contention is negligible for admin actions. A lock wait over 5 seconds returns 503 with `Retry-After`. Same order as D-32 for slice 005 |
| Q-04 | Role-change rules | The request body accepts only `ADMIN`, `AGENT`, `VIEWER`; `OWNER` is rejected as a validation error (no role editing assigns Owner). Allowed when: the target is ACTIVE (a suspended member is reactivated first); the actor is Owner, or an Admin acting on an Agent or Viewer and assigning Agent or Viewer. Promoting to Admin or demoting an Admin needs the "Assign or revoke Admin role" permission (Owner only). A target that is the Owner is rejected: 403 for an Admin actor (policy), 409 for the Owner. A member cannot change their own role (this follows from the rules above; it is tested explicitly for AC-MEM-12) |
| Q-05 | Recent authentication (AC-AUTH-14) is listed for promoting or demoting an Admin; a role change decides that only after reading the body | A small `RecentAuthentication.require(principal)` helper, extracted from the existing authorization manager, is called by the service for role changes that involve ADMIN. A stale session gets 403 `REAUTHENTICATION_REQUIRED`. Removing an Admin is not on the AC-AUTH-14 list, so it does not require it |
| Q-06 | Status transitions | Suspend: ACTIVE to SUSPENDED. Reactivate: SUSPENDED to ACTIVE. Remove: ACTIVE or SUSPENDED to REMOVED (final; rejoining needs a new invitation). Leave: the caller's ACTIVE membership to REMOVED, recorded as done by the member (D-20). Any other transition returns 409 `CONFLICT`. Only an Owner suspends, reactivates or removes an Admin; an Admin handles Agents and Viewers only (matrix). The Owner membership is never suspended, reactivated or removed through these routes. Suspension keeps the role |
| Q-07 | "Records who suspended it and when" (AC-MEM-13) and the before/after values the audit rules allow | Migration `V12`: `membership.status_changed_at` and `status_changed_by` (FK to `app_user`, nullable, set on every suspend, reactivate, remove and leave; the audit trail keeps the history). `audit_event.before_value` and `after_value` (nullable text, CHECK `^[A-Z_]{1,32}$`, so only role and status values fit). `company.ownership_resolution_at` (nullable timestamptz, the D-54 flag). All additive; no backfill, no rewrite of existing rows |
| Q-08 | Audit events | `membership.role.changed`, `membership.suspended`, `membership.reactivated`, `membership.removed`, `membership.left` (names already in `audit-events.md`), with company, actor, target membership, and before/after role or status. Written in the same transaction as the change. A denial for an active member is already audited (D-115, throttled by D-121). No names, emails or free text |
| Q-09 | Session effects (AC-AUTH-23) | A member who leaves has their session ID rotated after the commit, as AC-AUTH-23 requires for a change to their own membership. Other members' sessions are untouched: their next tenant request is denied by the per-request membership check (AC-MEM-04). No cache exists, so nothing needs invalidating |
| Q-10 | Member list contents | `id` (membership), `displayName`, `email`, `role`, `status`, `joinedAt`, and `self` (true for the caller). ACTIVE and SUSPENDED members only; REMOVED rows stay in the database and the audit trail. No user ID is exposed. Not paginated: the member count is bounded by the plan limit (slice 006). Needs a cap until then: at most 500 rows, which no company can reach before 006 |
| Q-11 | Error codes | New `CONFLICT` (409) in `ErrorCode`, the OpenAPI enum and ADR-005, for invalid transitions and for acting on the Owner as the Owner. A membership ID that is not in the company: 404 `NOT_FOUND` |
| Q-12 | AC-MEM-17 (reactivation limited by the plan) | Needs plans (slice 006). 003 adds an `ActiveMemberLimit` interface called from reactivation under the company lock, with a default that allows everything. Slice 006 supplies the real one. AC-MEM-17 stays Pending and is closed in 006 |
| Q-13 | AC-MEM-14 (a disabled account loses its sessions) and AC-MEM-18 (D-54) | AC-MEM-14 is already enforced by the session filter (slice 001); 003 adds a cross-reference test. For D-54: the flag column from Q-07, and a rewrite of the "Company effects" section of `operator-account-disable.md` with a reviewed SQL step that suspends the Owner membership and sets the flag in the same transaction as the disable. The operator transfer itself belongs to slice 005 (its runbook stub stays). A test runs the SQL against PostgreSQL: still exactly one Owner, Owner operations unavailable, Admin and Agent unaffected |
| Q-14 | AC-TEN-08 (related record of another company) | The mechanism was proved with test-only tables. In 003 a membership ID is the related record: a membership ID from another company in my company's path is rejected with 404 and changes nothing. That closes AC-TEN-08 for memberships; slice 004 repeats it for invitations |
| Q-15 | Rate limits for membership changes | None invented: the actors are authenticated Owners and Admins, and every change is audited. Residual risk: an Admin can create many audit rows by repeatedly changing roles; revisit with D-121 if abuse appears |
| Q-16 | Frontend | SPEC-MEM has no UI and `docs/design` has no member screens. No UI and no BFF route in 003 (same as D-120). The Postman collection is extended |
| Q-17 | Test-only routes from slice 002 | They stay for the permission rows that still have no real endpoint. For the rows 003 makes real (member list, role change, suspend, reactivate, remove, leave, assign or revoke Admin), a real-endpoint matrix test is added over all four roles and the three statuses. The HTTP test over the test routes stays, because it checks the wiring for every permission |
| Q-18 | Slice 002 left carry-forward items for this slice | Taken: authorization and mutation in one transaction with locks (Q-03); the ownership-race test repeated through the service (Phase 4); AC-TEN-08 (Q-14); the real-endpoint matrix (Q-17). Not taken: the server-wide request body limit (platform decision), `EXPORT_DATA` during pending deletion (slice 006) |

## 4. Phases (one PR each, in order; each branches from `main` after the previous one is merged)
### Phase 0: Governance (docs only)
Record the slice 002 closure (accepted risks, unrun checks); record Q-01 to Q-18 as D-123 onward; mark this plan Approved; add `ErrorCode` `CONFLICT` to the ADR-005 list; add the AC-MEM criteria to a traceability file as Pending; confirm or change the D-121 window.

### Phase 1: Schema and persistence
- Migration `V12__membership_status_audit.sql` (Q-07): additive columns and the CHECK on the audit values. Reviewed for locks: `ADD COLUMN` without a default is metadata-only. Checked on an empty database and on a database that already holds V1 to V11 data.
- `Membership` gets the status-change fields; `Company` gets the flag; `AuditLog` gets before/after.
- Locked repository methods: `CompanyRepository.findByIdForUpdate`, `MembershipRepository.findByCompanyIdAndIdForUpdate`, with a 5-second lock timeout hint. Scope test still passes.
- Tests on PostgreSQL: the new columns and CHECK, the lock timeout, the scope test.

### Phase 2: Authorization and domain rules
- `RecentAuthentication` helper (Q-05); `ErrorCode.CONFLICT` and the handler mapping (Q-11).
- `MembershipChanges` domain rules: allowed roles per actor (the SPEC-MEM table), allowed transitions, Owner protection, self-change denial. Pure and unit-tested against every actor, target, new-role and status combination.
- `MembershipService` skeleton with the transaction and lock sequence of Q-03 and `ActiveMemberLimit` (Q-12).

### Phase 3: Endpoints
- Contract first (Q-01), then the controller and the six operations, audit rows, session rotation on leave, DTOs (Q-10).
- Tests: AC-MEM-01, 02, 04, 05, 06, 07, 09, 10, 11, 12, 13, 15 over HTTP, and the real-endpoint matrix (Q-17).

### Phase 4: Isolation and concurrency
- Cross-tenant membership IDs (AC-TEN-08, Q-14); revocation on the same session for every status change.
- PostgreSQL concurrency, through the service: role change against removal of the same membership (AC-MEM-08); parallel attempts on the Owner by Owner and Admin (AC-MEM-03, 16); leave against suspend; two role changes of one member; the slice 002 ownership race repeated through the service.

### Phase 5: Ownership resolution, review and closure
- Runbook change and SQL test (Q-13, AC-MEM-18); independent security and code review (read-only agents), fixes, traceability, Postman collection and OpenAPI alignment, and the list of unrun checks.

## 5. Expected files
`contracts/openapi.yaml`; migration `V12`; in `com.example.saas.api`: `tenant/application` (`MembershipService`, `MembershipChanges`, `ActiveMemberLimit`, exceptions), `tenant/web` (`MemberController` and DTOs), `security/RecentAuthentication`, `ErrorCode.CONFLICT`, `AuditLog` before/after; repository methods; tests under `src/test/.../tenant`; `docs/specs/003-memberships/traceability.md`; `docs/runbooks/operations/operator-account-disable.md`; ADR-005; register entries; the Postman collection.

## 6. Acceptance-criteria coverage
| AC | Where |
|---|---|
| AC-MEM-01, 02 | Phase 2 (rules), Phase 3 (HTTP) |
| AC-MEM-03, 08, 16 | Phase 4 (PostgreSQL concurrency) on top of the Phase 1 schema guarantees |
| AC-MEM-04 | Phase 3 and 4 (same session, next request) |
| AC-MEM-05 | Already enforced and tested in slice 002 (`TenantSchemaTests`); cross-referenced |
| AC-MEM-06 | Phase 3 (audit rows contain ids and role or status values only) |
| AC-MEM-07, 09 | Phase 3 |
| AC-MEM-10, 11, 12, 15 | Phase 2 (all combinations) and Phase 3 (HTTP) |
| AC-MEM-13 | Phase 1 (columns) and Phase 3 |
| AC-MEM-14 | Slice 001 (already enforced); cross-reference test in Phase 3 |
| AC-MEM-17 | **Not in 003.** Hook in Phase 2; closed in slice 006 |
| AC-MEM-18 | Phase 5 (runbook SQL and test) |
| AC-AUTH-14 (promote or demote an Admin), AC-AUTH-23 | Closed for this consumer in Phase 3; AC-AUTH-14 stays open for the other four operations |

## 7. Risks
- The company-row lock serializes all membership changes in one company. Acceptable at this scale; revisit if a company ever has bursts of administration.
- Two connections per denied request (the denial audit runs in its own transaction while the outer one holds locks). The pool size must leave headroom; a test checks that denials under lock do not deadlock.
- A 5-second lock timeout turns a stuck transaction into a 503 instead of a hang; a 503 on a membership change must be retried by the client.
- Members' email addresses are shown to Owners and Admins in the member list. That follows the matrix but should be named in the privacy notes.
- The slice 002 test routes and the real routes both exist for a while; the real-endpoint matrix test must be kept in step with the policy table.

## 8. Checks that will be run per phase
`./mvnw verify` (Testcontainers PostgreSQL), contract lint, the API-type drift check in `apps/web` when the contract changes, and the CI jobs `api`, `contract`, `web`, `e2e`, CodeQL and dependency review. Migration `V12` is validated on an empty database and on one that already holds V1 to V11 rows. Checks not run are listed in the traceability file.

## 9. Approval (done 2026-10-21)
1. Q-01 to Q-18, in whole or with changes. The ones most worth a deliberate look: Q-03 (the company-row lock), Q-04 and Q-05 (role rules and recent authentication), Q-07 (new columns), Q-10 (what the member list shows), Q-12 and Q-13 (what is deferred or handled by a runbook).
2. The phase order.
3. The D-121 window: confirm 1 hour or set another value.

Answers recorded 2026-10-21: all of Q-01 to Q-18 approved without change; the phase order approved; the D-121 audit window confirmed at 1 hour.
