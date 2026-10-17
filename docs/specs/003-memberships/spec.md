# Membership and Role Management Specification

- ID: SPEC-MEM
- Status: Approved
- Re-approved on 2026-10-17. Approver: product owner.
- Requirements: TEN-003 through TEN-006

## Objective
Manage company membership and roles without allowing privilege escalation or loss of the last Owner.

## Rules
- Each membership belongs to one user and one company.
- Membership status and role are authoritative server-side values.
- All membership changes require permission under the approved matrix.
- A company always has exactly one Owner membership (D-43, decided 2026-10-16), and it is active except while the company is flagged for ownership resolution after an operator disables the Owner's account (D-54). Role changes and removals preserve this.
- Exactly one Owner membership per company is enforced by a partial unique index on Owner memberships, plus a company-row lock on every Owner-affecting write (D-23, D-32, D-43).
- Cross-tenant related IDs are rejected.

## Decisions

### Role assignment
The role hierarchy is Owner > Admin > Agent > Viewer. Hierarchy alone does not determine permissions; permissions are defined explicitly.

| Actor  | Roles they can assign | Restrictions                                                  |
| ------ | --------------------- | ------------------------------------------------------------- |
| Owner  | Admin, Agent, Viewer  | Cannot assign or transfer Owner through ordinary role editing |
| Admin  | Agent, Viewer         | Cannot create Admins or modify Owner                          |
| Agent  | None                  | Cannot manage memberships                                     |
| Viewer | None                  | Cannot manage memberships                                     |

- Users cannot grant permissions they do not possess.
- Users cannot change their own role to escalate privileges.
- Only an Owner can promote a member to Admin or demote an Admin.
- Role changes are authorized by the backend, not just hidden in the UI.

### Removing an Admin
Only an Owner can remove an Admin. An Admin cannot remove another Admin.

### Leaving a company
- Admins, Agents, and Viewers can leave a company themselves.
- The Owner cannot leave; ownership must first be transferred (D-43).
- Leaving revokes access to that tenant but does not delete the user's account.
- A user who leaves loses access to tenant resources immediately, including access through already-open browser sessions.

### Owner account disabled or deleted
An active company never becomes ownerless. Account deactivation, account deletion, and company deletion are distinct. The behavior below applies to V1 (decision D-22, decided 2026-10-16).

| Situation                                             | Behavior                                                                                                                                     |
| ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Owner account is disabled by an operator              | Block authentication immediately and suspend the account's access to all tenants. Flag each affected company for ownership resolution.       |
| Owner requests account deletion                       | Reject or defer permanent deletion while they are the Owner of an active company. Require ownership transfer or company deletion first. |
| Owner account is deleted after ownership is resolved  | Remove or anonymize personal data according to the retention policy, preserving required audit records.                                      |
| Company is being permanently deleted                  | Follow the company's deletion and retention workflow; this is not an ordinary account deletion.                                              |

When an operator disables an Owner's account (D-54, decided 2026-10-16), the company is flagged for ownership resolution. The Owner membership stays the single Owner membership but is suspended, Owner-only operations are unavailable, and company deletion is rejected. An operator then performs an audited transfer to an active member after verifying the request.

In V1, account disable and account deletion are not user-facing flows. An operator performs them through an audited runbook, and the rules above constrain that runbook.

If the user's entire account is disabled, their authenticated sessions are invalidated or rejected globally.

### Membership removal and existing sessions
Membership removal takes effect on the next protected API request, without requiring the user to log out. A session can remain valid for authentication while its tenant authorization has been revoked, so deleting a cookie or invalidating a browser session is not sufficient.

- Every protected tenant request verifies that the authenticated user still has an active membership and sufficient permissions for the requested tenant.
- Removing a membership or changing a role takes effect immediately for subsequent authorization checks.
- The removal transaction commits before the API reports success.
- If authorization data is cached, the cache is invalidated as part of the operation or a versioning strategy prevents stale permissions from being accepted.
- Requests already in progress may complete unless cancellation is explicitly implemented; requests starting after revocation are denied.

## Open items
- The retention policy referenced for deleted accounts (decision D-40).

## Acceptance criteria
- AC-MEM-01: Unauthorized users cannot change membership or role.
- AC-MEM-02: Role changes are validated against the permission matrix.
- AC-MEM-03: The Owner cannot be removed, suspended, or demoted except through ownership transfer, including under concurrent requests. This is the owning criterion for TEN-006; AC-TEN-06 (SPEC-TEN) references it.
- AC-MEM-04: Membership revocation blocks subsequent protected requests.
- AC-MEM-05: Duplicate memberships are prevented by a database constraint.
- AC-MEM-06: Changes are audited without sensitive data.
- AC-MEM-07: An Admin, Agent, or Viewer can leave a company; leaving ends their membership, denies access to that company's resources on their next request, and does not delete their user account.
- AC-MEM-08: Concurrent role-change and removal requests targeting the same membership leave one consistent final state and never leave the company without an active Owner.
- AC-MEM-09: The Owner cannot leave the company; ownership must first be transferred.
- AC-MEM-10: An actor can assign only the roles the role-assignment table permits: Owner may assign Admin, Agent, or Viewer; Admin may assign Agent or Viewer; Agent and Viewer may assign none; no actor can assign Owner through role editing.
- AC-MEM-11: Only an Owner can promote a member to Admin, demote an Admin, or remove an Admin.
- AC-MEM-12: A user cannot change their own role to a more privileged role.
- AC-MEM-13: A suspended membership denies all access to the company on the next request, preserves the member's role, and records who suspended it and when; reactivation is recorded the same way.
- AC-MEM-14: Disabling a user account invalidates or rejects that user's sessions globally.
- AC-MEM-15: An Admin cannot change the role of, suspend, reactivate, or remove an Owner or another Admin; these attempts are rejected, while the same actions on an Agent or Viewer are permitted subject to AC-MEM-10.
- AC-MEM-16: A company has exactly one Owner membership after every committed transition, including under concurrent requests; that membership is active except while the company is flagged for ownership resolution (D-54).
- AC-MEM-17: Reactivating a suspended membership is rejected if it would exceed the company's plan limit on active members, including under concurrent requests (PLAN-003, PLAN-005; SPEC-PLAN).
- AC-MEM-18: While an Owner's account is disabled, the company is flagged for ownership resolution, Owner-only operations and company deletion are rejected, and the company still has exactly one Owner membership.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| TEN-003 | AC-MEM-02, AC-MEM-05, AC-MEM-10, AC-MEM-11, AC-MEM-12, AC-MEM-15 |
| TEN-004 | AC-MEM-01 |
| TEN-005 | AC-MEM-01, AC-MEM-02, AC-MEM-04, AC-MEM-07, AC-MEM-13, AC-MEM-14 |
| TEN-006 | AC-MEM-03 (owning), AC-MEM-08, AC-MEM-09, AC-MEM-16, AC-MEM-18 |
| PLAN-003, PLAN-005 (supporting) | AC-MEM-17 |
| NFR-SEC-006 (supporting) | AC-MEM-06 |

## Tests
Role matrix unit tests, PostgreSQL integration tests, session/membership revocation tests, cross-tenant tests, and concurrent last-Owner tests.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
