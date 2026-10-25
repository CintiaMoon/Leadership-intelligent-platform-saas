# Company and Tenant Specification

- ID: SPEC-TEN
- Status: Approved
- Re-approved on 2026-10-17, including the later edits that day. Approver: product owner.
- Requirements: TEN-001 through TEN-006

## Objective
Create companies and establish tenant-scoped membership and access control.

## Behavior
- An authenticated, verified user can create a company if policy allows.
- Company creation and initial Owner membership are atomic.
- Company-owned records are accessed only after resolving a trusted company context and verifying active membership.
- The tenant context is the company ID in the URL path (`/api/v1/companies/{companyId}/…`). It is a selector only; membership and permission are verified on every request (D-03, decided 2026-10-16).
- Roles are evaluated by centralized server-side policies.
- All relevant data-access methods scope queries to the authorized company.

## Decisions

### Multiple companies
A single user account can belong to multiple companies, with a different role in each one.

### Role-permission matrix
Permissions are defined explicitly in the matrix below, not through role names or scattered conditionals. The matrix is enforced on the backend. The frontend reflects the same policy but is not the security boundary.

The matrix assumes Agents work with the product's core business records.
- "Agent/Viewer only" means an Admin may act only on memberships whose role is Agent or Viewer, never on an Owner or another Admin (invariants 3 and 4; SPEC-MEM role-assignment table).
- "View only" means read access to plan and entitlement data without the right to change it; only an Owner changes the plan (the Owner's authority over the plan is stated in "Role boundaries"). Confirmed 2026-10-16. The "View only" cell on the manage-plans row and the "View plan and entitlements" row mean the same thing for Admin.
- Core business records are questionnaires (including their versions), assessment runs, and the people directory. Reports and dashboards are those derived from them (decision D-49).
- Export additionally requires the company's plan to include the export entitlement (CSV export is part of the Trial, Starter, and Team plans in SPEC-PLAN). Export is blocked during an expired trial's 14-day read-only window (SPEC-PLAN).
- Revoking or resending an invitation has the same role permissions as inviting, and an actor can revoke or resend only invitations for roles that actor may invite (SPEC-INV, AC-INV-17); its semantics are decision D-25.
- Some operations are not role-based: creating a company (any verified user, see "Company creation"), accepting an invitation (the invited user with a matching verified email, SPEC-INV), and accepting an ownership transfer (the named recipient, SPEC-OWN).
- Any operation not listed in the matrix or in the previous bullet is denied.
- While a company is PENDING_DELETION, every state-changing row of the matrix is denied except the Owner's cancellation of the deletion, including "Leave the company" (SPEC-DEL, AC-DEL-12).

| Permission | Owner | Admin | Agent | Viewer |
|---|:---:|:---:|:---:|:---:|
| View company details | Yes | Yes | Yes | Yes |
| View core business records | Yes | Yes | Yes | Yes |
| Create core business records | Yes | Yes | Yes | No |
| Edit core business records | Yes | Yes | Yes | No |
| Delete core business records | Yes | Yes | No | No |
| View reports and dashboards | Yes | Yes | Yes | Yes |
| Export data | Yes | Yes | No | No |
| Invite new members | Yes | Yes | No | No |
| Revoke or resend invitations | Yes | Yes | No | No |
| View member list | Yes | Yes | No | No |
| Change a member's role | Yes | Agent/Viewer only | No | No |
| Suspend or remove members | Yes | Agent/Viewer only | No | No |
| Reactivate suspended members | Yes | Agent/Viewer only | No | No |
| Leave the company | No (transfer ownership first) | Yes | Yes | Yes |
| Assign or revoke Admin role | Yes | No | No | No |
| Transfer company ownership | Yes | No | No | No |
| Cancel ownership transfer | Yes (the initiator) | No | No | No |
| Accept ownership transfer | Yes | Yes | Yes | Yes |
| Manage company settings | Yes | Yes | No | No |
| Manage plans and entitlements | Yes | View only | No | No |
| View plan and entitlements | Yes | Yes | No | No |
| Request company deletion | Yes | No | No | No |
| Cancel pending company deletion | Yes | No | No | No |
| Permanently delete company data | System process | System process | System process | System process |

Accept ownership transfer is identity-based, like leaving the company: every role has the row, and the service allows only the member named as the recipient of a pending transfer (SPEC-OWN, D-181).

### Role boundaries
- Owner: full control over the company, its membership, plan, ownership, and lifecycle. A company always has exactly one active Owner (D-43, decided 2026-10-16), except while it is flagged for ownership resolution after an operator disables the Owner's account (D-54).
- Admin: manages daily company administration, settings, invitations, and membership. Cannot transfer ownership, assign or revoke the Owner role, or delete the company.
- Agent: performs operational work using core product features. Cannot manage members, company settings, billing, or lifecycle.
- Viewer: read-only access to permitted records, dashboards, and reports. Cannot create, modify, or delete business data.

### Authorization rules
- An Admin cannot promote themselves or another user to Owner.
- Only an Owner can initiate an ownership transfer.
- No user can remove, demote, or suspend the Owner except through ownership transfer.
- An Owner cannot eliminate their own ownership except through a valid transfer to another active member.
- Role changes take effect on the next authorized request, not only after a new login.
- Ownership transfer and deletion require server-side authorization regardless of what the UI displays.
- If billing is introduced later, billing permissions are separate from company administration; Admins do not automatically receive all financial actions.
- Authorization is implemented in a centralized policy component (for example `CompanyAuthorizationService`). Each permission is tested against each role. Role checks are not scattered through controllers.

### Company creation
- Any user with a verified email and an active account can create a company, subject to abuse-prevention controls.
- The user's account must be ACTIVE, not DISABLED (D-56).
- Company creation is rate limited per IP address to 5 creations per hour (matching the registration default in SPEC-AUTH) and per account to 10 creations per day (decided 2026-10-16).
- The user becomes the initial Owner.
- The company and the initial Owner membership are created in the same database transaction. If creation fails, neither is left partially created.
- Trial eligibility is evaluated separately from permission to create a company.
- Plan limits and entitlements are enforced by the backend.

### Membership suspension and removal
Membership status is kept separate from membership role and distinguishes suspension from removal. Status values: ACTIVE, SUSPENDED, REMOVED (a member who leaves is recorded as REMOVED, with the member as the actor). Only ACTIVE memberships count as active members and active Owners. A role determines what a person can do; a status determines whether the person can access the company at all.

Suspension is reversible. It is used to block a member temporarily without ending the membership relationship.
- Preserves the user's role and membership history.
- Denies all company access immediately.
- An authorized Owner or Admin can reactivate the membership, subject to role restrictions.
- Records who suspended or reactivated the membership and when.

Removal ends the active membership.
- Revokes access immediately.
- Preserves historical records and audit events.
- Invalidates or rejects company-specific access derived from old sessions or tokens.
- Requires a new invitation to rejoin.
- Does not delete the global user account; the user may belong to other companies.

### Pending deletion
Pending deletion is a restricted lifecycle state, not ordinary active operation. The Owner has a grace period to cancel deletion. Normal business activity is blocked and new access is not granted. The blocked operations and the deletion workflow are defined in SPEC-DEL (AC-DEL-12, AC-DEL-14). Reads are limited to Owners.

## Security and consistency rules
- Every tenant-scoped request verifies that the membership is ACTIVE, not merely that the user is authenticated.
- A user's suspension in Company A does not affect their active membership in Company B.
- Role changes, suspension, and removal are enforced on subsequent requests, even when a session remains valid.
- Suspension, removal, and demotion of the Owner are rejected except through ownership transfer.
- Concurrent membership changes and ownership transfers do not violate the invariant that a company always has exactly one active Owner, except while flagged for ownership resolution (D-54).
- An audit log records membership creation, role changes, suspension, reactivation, removal, and ownership changes.

## Deletion workflow
The deletion workflow (request, pending deletion, cancellation, purge, and tombstone) is owned by SPEC-DEL (`docs/specs/007-company-deletion/spec.md`). This spec states only the effects on membership and access under "Pending deletion" above.

## Open items
- Security-owner sign-off of the role-permission matrix (decision D-18). Product-owner sign-off was recorded on 2026-10-17, and the security-engineer agent reviewed the matrix on 2026-10-16.

## Acceptance criteria
- AC-TEN-01: Company and initial Owner membership commit atomically.
- AC-TEN-02: Failed company creation leaves no orphan company or membership.
- AC-TEN-03: A user without active membership cannot read company data.
- AC-TEN-04: Changing a company ID cannot grant access to another tenant.
- AC-TEN-05: Client-supplied role values are never authoritative.
- AC-TEN-06: The Owner cannot be removed or demoted except through ownership transfer. Owned and tested under AC-MEM-03 (SPEC-MEM), including concurrent requests.
- AC-TEN-07: A user whose email is not verified cannot create a company.
- AC-TEN-08: A request that references a related record belonging to a different company than the authorized tenant context is rejected and changes no data.
- AC-TEN-09: Suspending or removing a user's membership in one company does not affect their active membership or access in another company.
- AC-TEN-10: Every Yes/No cell and every defined-condition cell ("Agent/Viewer only", "View only") of the role-permission matrix is tested for every role: granted permissions succeed and every other role-permission pair is denied by the backend. Operations not listed in the matrix or in the not-role-based list are denied.
- AC-TEN-11: A user whose account is DISABLED cannot create a company.
- AC-TEN-12: An Admin can view the company's plan and entitlements but cannot change them; a plan change requested by an Admin, Agent, or Viewer is rejected.
- AC-TEN-13: The 6th company-creation attempt from one IP address within an hour is throttled.
- AC-TEN-14: A data export is rejected for an Agent or Viewer, and for an Owner or Admin whose company's plan lacks the export entitlement or whose expired trial is within its 14-day read-only window.
- AC-TEN-15: The 11th company-creation attempt by one account within a day is throttled.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| TEN-001 | AC-TEN-07, AC-TEN-11, AC-TEN-13, AC-TEN-15 |
| TEN-002 | AC-TEN-01, AC-TEN-02 |
| TEN-003 | AC-TEN-10, AC-TEN-12, AC-TEN-14, AC-MEM-15 (Admin target limits) |
| TEN-004 | AC-TEN-03, AC-TEN-04, AC-TEN-08, AC-TEN-09 |
| TEN-005 | AC-TEN-05, AC-TEN-10 |
| TEN-006 | AC-TEN-06 (references AC-MEM-03, the owning criterion in SPEC-MEM) |

## Tests
Use a real PostgreSQL test database. Test cross-tenant reads/writes, revoked memberships, related-record tenant mismatches, atomic creation, and concurrent Owner removal.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
