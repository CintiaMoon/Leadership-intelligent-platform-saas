# Implementation Plan: SPEC-TEN (slice 002)

- Status: **Approved 2026-10-21** (user: "approve all"): Q-01 to Q-16 and the phase order; recorded as D-105 to D-120. Phases 0 to 4 are merged. Phase 5 (independent reviews and fixes) is done on its branch; D-121 and D-122 were approved and implemented on 2026-10-21 (see `traceability.md`).
- Spec: `docs/specs/002-tenants/spec.md` (SPEC-TEN, Approved 2026-10-17)
- Related: `docs/architecture/invariants.md`, `data-model.md`, `threat-model.md`, ADR-001, ADR-004, ADR-005; decisions D-03, D-17, D-18, D-19, D-20, D-23, D-43, D-47, D-49, D-54, D-56, D-89
- Date: 2026-10-21. Written from the spec, the register, and the Slice 001 code; no code was changed.

## 1. Readiness check (CLAUDE.md workflow step 1)
| Item | State | Action |
|---|---|---|
| D-18 (permission matrix) | Blocked 002 until 2026-10-21. Product-owner sign-off 2026-10-17; **security-owner sign-off recorded 2026-10-21** | Cleared. The matrix is signed off as it stands in the spec |
| D-03, D-17, D-19, D-20, D-23, D-43, D-47, D-49, D-56 | Decided | None |
| D-54 (Owner account disabled) | Decided; the flagged state is built in slice 003 | 002 must not forbid it: the Owner membership may be SUSPENDED while the company is flagged (Q-07) |
| D-89 (consumers of verified-only, recent-auth, rotation) | Decided | 002 is the first consumer of "verified-only" (company creation) |
| D-68 (core domain spec) | Open, but it blocks 006 and 007 only | The matrix rows for core records cannot be exercised on real endpoints. Test-only endpoints are used (Q-12) |
| Plan, invitation, deletion and ownership slices | Not built | Matrix rows owned by slices 003 to 007 are tested at policy level now and at endpoint level in their own slice |

## 2. Assumptions and trust boundaries
- Browser untrusted; BFF authenticated by its credential; the API is the only authority. A `companyId` in the path is a selector, never proof of access (D-03).
- Identity comes from the server-side session only. **The role is never stored in the session or read from the request.** It is loaded from the database on every request, so a role change, suspension or removal applies to the next request (spec, "Authorization rules").
- Every tenant-owned table carries `company_id`, and every repository method on such a table takes it.

## 3. Gaps found, with proposed answers
| ID | Gap | Proposal |
|---|---|---|
| Q-01 | Company name rules are not specified | 1 to 100 code points after NFKC and trim, no control characters, not required to be unique. Same normalizer family as the display name |
| Q-02 | Company lifecycle states | `ACTIVE` and `PENDING_DELETION` as a text column with a CHECK. 002 only creates `ACTIVE`; 007 owns the transitions. The authorization service already denies state-changing permissions in `PENDING_DELETION` (spec) |
| Q-03 | Endpoints are not defined (spec: examples are not approved until added to OpenAPI) | `POST /api/v1/companies` (201; body: `id`, `name`, `role`), `GET /api/v1/companies` (the caller's ACTIVE memberships: `id`, `name`, `role`), `GET /api/v1/companies/{companyId}` (permission "View company details"). Added to `contracts/openapi.yaml` before the code |
| Q-04 | Response for a company the caller is not an active member of | **404 `NOT_FOUND` for no membership, suspended, removed, or unknown company** (one response, so existence is not revealed). **403 `FORBIDDEN` only for an active member who lacks the permission.** Needs a new `NOT_FOUND` error code (ADR-005 list) |
| Q-05 | How authorization is structured | `Role` and `Permission` enums; one `CompanyPolicy` holding the matrix as a single table; `CompanyAuthorizationService.require(userId, companyId, permission)` returning an `AuthorizedCompany(companyId, userId, role)`; a `HandlerMethodArgumentResolver` so controllers receive it and never read the path variable themselves. No role checks in controllers |
| Q-06 | Tenant-scoped data access can be forgotten | A reflection test fails the build if a repository of a tenant-owned entity has a query method without a `companyId` parameter. No new dependency (ArchUnit would be one and needs its own approval) |
| Q-07 | Membership uniqueness | One row per join. Partial unique index on `(company_id, user_id)` where status is ACTIVE or SUSPENDED (a removed member who is re-invited gets a new row, so history stays). Partial unique index on `(company_id)` where role is OWNER and status is ACTIVE or SUSPENDED (D-23, D-43, D-54) |
| Q-08 | "Exactly one Owner" lower bound at creation | A `DEFERRABLE INITIALLY DEFERRED` constraint trigger checks at commit that each company has one Owner membership. This is the database half of AC-TEN-01 and AC-TEN-02. Trade-off: slice 007's purge must delete the company and its memberships in one transaction. Later changes to the Owner: D-23 index plus a company-row lock (slices 003 and 005) |
| Q-09 | Rate limits | Reuse the Slice 001 limiter: IP 5 per hour (`company-create-ip`) and account 10 per day (`company-create-account`), counted per attempt before validation, fail closed with 503, 429 with `Retry-After` (AC-TEN-13, 15) |
| Q-10 | Verified and active gate | Reuse the D-89 verified-only authorization manager on `POST /api/v1/companies`. A DISABLED account is already rejected by the session filter (AC-TEN-07, 11) |
| Q-11 | Audit | `tenant.company.created` and `tenant.membership.created` (success) in the creation transaction. A permission denial for an active member is audited as `tenant.authorization.denied` with the permission code in `reason_code`. No-membership 404s are not audited, to avoid flooding the trail |
| Q-12 | The matrix has no real endpoints yet | A test-only controller (test source tree, as in Slice 001) exposes one route per matrix permission under `/api/v1/companies/{companyId}/test/...`. Real endpoints replace them as slices 003 to 007 land. A policy-level test runs the whole table now |
| Q-13 | The matrix can drift from the spec | A test parses the permission table in `spec.md` and compares it cell by cell with `CompanyPolicy`, so changing one without the other fails the build |
| Q-14 | AC-TEN-08 (related record from another company) needs related records | Verified here as a mechanism (the Q-06 rule plus a test-only table and repository in a test Flyway location). **Decided: tested now.** The criterion is closed in slice 003, where membership and invitation records reference each other |
| Q-15 | Limit on companies per user | The spec sets only the creation rate (10 per day). No total cap is invented. **Decided: no cap.** |
| Q-16 | Frontend | SPEC-TEN has no UI and `docs/design` has no company screens. Proposal: **no UI and no BFF route in 002**; a UI needs a design first. The Postman collection is extended |

## 4. Phases (one PR each, in order; each leaves `main` green)
### Phase 0: Governance (docs only)
Record the security-owner sign-off of D-18 (or amend the matrix); record Q-01 to Q-16 as D-105 onward; accept this plan; add the AC-TEN criteria to a traceability file as Pending.

### Phase 1: Schema and domain
- Migrations `V9__create_company.sql` and `V10__create_membership.sql` (indexes and trigger from Q-07 and Q-08), reviewed for locks (new tables only, no backfill, nothing destructive).
- `Company` and `Membership` entities, `Role`, `MembershipStatus`, repositories (every tenant query takes `companyId`).
- Tests on PostgreSQL: unique indexes, check constraints, the deferred trigger (no company without an Owner), cascade behaviour.

### Phase 2: Authorization core
- `Permission`, `CompanyPolicy` (the matrix), `CompanyAuthorizationService`, the `AuthorizedCompany` argument resolver, `NOT_FOUND` error code, denied-audit.
- Tests: the whole matrix for every role (AC-TEN-10 at policy level), the spec-table parity test (Q-13), the repository-scope reflection test (Q-06), denial of state-changing permissions in `PENDING_DELETION`.

### Phase 3: Company creation and reading
- Contract first, then `CompanyController`, `CompanyService` (one `@Transactional` boundary for the company, the Owner membership and the audit rows; no email, so no outbox), rate limits, verified gate.
- Tests: AC-TEN-01, 02 (a forced failure after the company insert leaves nothing), 05 (a `role` in the body is rejected as an unknown field), 07, 11, 13, 15, plus the 404 versus 403 behaviour.

### Phase 4: Tenant isolation and concurrency
- Test-only endpoints per permission (Q-12); cross-tenant read and write attempts with a swapped `companyId`; suspended and removed memberships set directly in the database; one user in two companies (AC-TEN-03, 04, 09); a role changed in the database takes effect on the next request with the same session.
- Concurrency on PostgreSQL: parallel creations by one account respect the daily limit exactly; parallel membership writes cannot produce two Owners.

### Phase 5: Review and closure
Independent security and code review (read-only agents), fixes, traceability, Postman collection and OpenAPI alignment, and the list of unrun checks.

## 5. Expected files
`contracts/openapi.yaml`; migrations `V9` and `V10`; packages `tenant/domain`, `tenant/application`, `tenant/web` and `tenant/security` under `com.example.saas.api`; `ErrorCode.NOT_FOUND`; a `SecurityConfig` matcher for the verified gate; tests under `src/test/.../tenant`; `docs/specs/002-tenants/traceability.md`; register entries; the Postman collection.

## 6. Acceptance-criteria coverage
| AC | Where |
|---|---|
| AC-TEN-01, 02 | Phase 1 (trigger) and Phase 3 |
| AC-TEN-03, 04, 09 | Phase 4 |
| AC-TEN-05 | Phase 3 |
| AC-TEN-06 | **Not in 002.** Owned by AC-MEM-03 (slice 003). 002 supplies the unique Owner index |
| AC-TEN-07, 11, 13, 15 | Phase 3 |
| AC-TEN-08 | Mechanism in 002 (Q-14); closed in slice 003 |
| AC-TEN-10 | Policy level in Phase 2; endpoint level for rows that get real endpoints; the remaining rows in slices 003 to 007 |
| AC-TEN-12, 14 | **Not in 002.** They need plans and export (slice 006, D-73). The matrix cells are tested, the entitlement behaviour is not |

## 7. Risks
- Two copies of the matrix (spec and code) can diverge. Mitigation: the parity test.
- A forgotten `companyId` in a query is a cross-tenant leak. Mitigation: Q-06 and the Phase 4 tests, not convention alone.
- The deferred trigger makes the order of deletion matter in slice 007.
- A 404 for a suspended member hides why access ended; a later UI must explain this without confirming that the company exists.
- Without a cap on companies per user (Q-15), one account can create up to 10 per day indefinitely.

## 8. Checks that will be run per phase
`./mvnw verify` (Testcontainers PostgreSQL), contract lint, the API-type drift check in `apps/web` (only if the contract changes), and the CI jobs `api`, `contract`, `web`, `e2e`, CodeQL and dependency review. Migrations are validated on an empty database. Checks not run are listed in the traceability file.

## 9. Approval needed
1. ~~Security-owner sign-off of D-18~~ Done 2026-10-21.
2. Q-01 to Q-16, in whole or with changes. Answered: Q-04, Q-08, Q-14, Q-15. Open: **Q-16** (no UI in this slice) and the remaining proposals.
3. The phase order.

## 10. Answers recorded (2026-10-21, from the user)
| ID | Answer |
|---|---|
| Q-04 | Yes: 404 `NOT_FOUND` when the caller has no active membership (or the company does not exist); 403 `FORBIDDEN` only for an active member who lacks the permission |
| Q-08 | Yes: the deferred constraint trigger that requires an Owner membership at commit |
| Q-14 | Test AC-TEN-08 as a mechanism now (test-only table in a test Flyway location); close it in slice 003 |
| Q-15 | No cap on the number of companies per user; only the creation rate limits apply |

| D-18 | Signed off by the user on 2026-10-21 (instruction "sign it off"), on the matrix in `spec.md` unchanged. Recorded in the register |

Q-01 to Q-03, Q-05 to Q-07, Q-09 to Q-13, Q-16 and the phase order were approved on 2026-10-21 ("approve all"). Q-NN maps to D-(104+NN).
