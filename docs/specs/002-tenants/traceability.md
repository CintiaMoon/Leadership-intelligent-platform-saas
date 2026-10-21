# SPEC-TEN Traceability (implementation status)

- Updated: 2026-10-21, after Phase 3 (company creation and reading). AC-TEN-01, 02, 05, 07, 11, 13, 15 are verified; AC-TEN-03, 04, 08, 09 wait for Phase 4; AC-TEN-10 is verified at policy level; AC-TEN-06, 12, 14 belong to later slices.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-105 to D-120.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-TEN-01 | Verified | Phase 1 (trigger), Phase 3 | `CompanyApiTests.creatingACompanyMakesTheCallerItsOwnerAndAuditsIt`, `TenantSchemaTests.aCompanyAndItsOwnerCommitTogether`, `aCompanyWithoutAnOwnerCannotCommit` |
| AC-TEN-02 | Verified | Phase 1 (trigger), Phase 3 | `CompanyCreationFailureTests` (database failure after the inserts leaves nothing; the Owner trigger independently guarantees it: removing `@Transactional` did not fail the test), `TenantSchemaTests.aFailureAfterTheCompanyInsertLeavesNothing` |
| AC-TEN-03 | Pending | Phase 4 | |
| AC-TEN-04 | Pending | Phase 4 | |
| AC-TEN-05 | Verified | Phase 3 | `CompanyApiTests.aRoleOrAnyOtherUnknownFieldInTheBodyIsRejectedAndNothingIsCreated` (`role`, `companyId`, `userId` fields) |
| AC-TEN-06 | Pending | Not in 002: owned by AC-MEM-03 (slice 003); 002 supplies the unique Owner index | |
| AC-TEN-07 | Verified | Phase 3 | `CompanyApiTests.anAccountThatIsNoLongerVerifiedCannotCreateACompany`, `creationNeedsASessionAndTheSessionBoundCsrfToken` (a request without a live session is refused with 401 or 403 and creates nothing) |
| AC-TEN-08 | Pending | Mechanism in Phase 4 (Q-14); closed in slice 003 | |
| AC-TEN-09 | Pending | Phase 4 | |
| AC-TEN-10 | Partial (policy level) | Policy level in Phase 2; endpoint level in Phase 4 (test-only routes); real endpoints in slices 003 to 007 | `CompanyPolicyTest`, `SpecMatrixParityTest` (every Permission x Role cell equals the spec table; mutation check: changing one cell fails it) |
| AC-TEN-11 | Verified | Phase 3 | `CompanyApiTests.aDisabledAccountCannotCreateACompany` |
| AC-TEN-12 | Pending | Not in 002: needs plans (slice 006); matrix cells tested in Phase 2 | |
| AC-TEN-13 | Verified | Phase 3 | `CompanyApiTests.theSixthAttemptFromOneAddressWithinAnHourIsThrottledAndInvalidAttemptsCount`, `CompanyFailClosedTests` (503 when the store is down) |
| AC-TEN-14 | Pending | Not in 002: needs export and plans (slice 006, D-73); matrix cells tested in Phase 2 | |
| AC-TEN-15 | Verified | Phase 3 | `CompanyApiTests.theEleventhAttemptByOneAccountWithinADayIsThrottled` |

## Checks not run
Phase 3: `./mvnw verify` ran (312 tests, 0 failures, 2 skipped); `npm run lint` in `contracts` passed (2 warnings, not examined); `gen:api` regenerated `schema.d.ts`; `npm run typecheck` in `apps/web` printed no errors. Not run: frontend unit tests, lint and build, Playwright E2E, CI jobs. No BFF route or UI exists for these endpoints (D-120).

## Phase 2 evidence (not tied to one AC)
- 404 vs 403 (D-108), role loaded per request, suspension isolated per company, pending-deletion reads: `CompanyAuthorizationTests` (9; contributes to AC-TEN-03, 04, 09, which stay Pending until Phase 4).
- Repository scope (D-110): `TenantRepositoryScopeTest`.
- Denial audit and no audit for 404 probes (D-111): `CompanyAuthorizationTests`.

## Phase 3 evidence (not tied to one AC)
- Listing and detail (`GET /api/v1/companies`, `GET /api/v1/companies/{companyId}`): `CompanyApiTests` covers own companies only, suspended and removed excluded, pending-deletion companies listed only for the Owner, 404 for a stranger and an unknown company with the same body, 403 plus a denial audit row for an Agent on a pending-deletion company, and no internal fields in responses.
- Name rule (D-105): `CompanyNameTest`, `CompanyApiTests.invalidNamesAreRejectedWithAFieldErrorAndNothingIsCreated`, `theNameIsNormalizedAndNamesAreNotUnique`.
- Known gap: an unknown field or malformed JSON is rejected while parsing, before the rate limits count the attempt (D-107 says "before validation"; parsing precedes the controller).
