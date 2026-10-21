# SPEC-TEN Traceability (implementation status)

- Updated: 2026-10-21, after Phase 4 (tenant isolation and concurrency). Verified: AC-TEN-01, 02, 03, 04, 05, 07, 09, 10 (every cell over HTTP), 11, 13, 15. AC-TEN-08 is verified as a mechanism and closed in slice 003. AC-TEN-06, 12, 14 belong to later slices. Phase 5 (reviews and closure) is next.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-105 to D-120.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-TEN-01 | Verified | Phase 1 (trigger), Phase 3 | `CompanyApiTests.creatingACompanyMakesTheCallerItsOwnerAndAuditsIt`, `TenantSchemaTests.aCompanyAndItsOwnerCommitTogether`, `aCompanyWithoutAnOwnerCannotCommit` |
| AC-TEN-02 | Verified | Phase 1 (trigger), Phase 3 | `CompanyCreationFailureTests` (database failure after the inserts leaves nothing; the Owner trigger independently guarantees it: removing `@Transactional` did not fail the test), `TenantSchemaTests.aFailureAfterTheCompanyInsertLeavesNothing` |
| AC-TEN-03 | Verified | Phase 4 | `TenantIsolationTests.aUserWithoutActiveMembershipGetsNotFoundOnEveryRoute` (outsider, SUSPENDED and REMOVED on all 23 routes), `CompanyAuthorizationTests` |
| AC-TEN-04 | Verified | Phase 4 | `TenantIsolationTests.swappingTheCompanyIdNeverGrantsAnotherTenantsData` (own company vs another's on all 23 routes; another company's record ID under my company) |
| AC-TEN-05 | Verified | Phase 3 | `CompanyApiTests.aRoleOrAnyOtherUnknownFieldInTheBodyIsRejectedAndNothingIsCreated` (`role`, `companyId`, `userId` fields) |
| AC-TEN-06 | Pending | Not in 002: owned by AC-MEM-03 (slice 003); 002 supplies the unique Owner index | |
| AC-TEN-07 | Verified | Phase 3 | `CompanyApiTests.anAccountThatIsNoLongerVerifiedCannotCreateACompany`, `creationNeedsASessionAndTheSessionBoundCsrfToken` (a request without a live session is refused with 401 or 403 and creates nothing) |
| AC-TEN-08 | Mechanism verified; closed in slice 003 | Mechanism in Phase 4 (Q-14); closed in slice 003 | `TenantIsolationTests.aRelatedRecordFromAnotherCompanyIsRejectedAndNothingChanges`, `theDatabaseAlsoRefusesANoteThatPointsAtAnotherCompanysRecord` (test-only tables `test_record` and `test_note`, composite foreign key) |
| AC-TEN-09 | Verified | Phase 4 | `TenantIsolationTests.suspendingOrRemovingAMembershipInOneCompanyLeavesTheOthersUntouched`, `aRoleInOneCompanyGivesNothingInAnother`, `CompanyAuthorizationTests.oneCompanysSuspensionDoesNotAffectAnother` |
| AC-TEN-10 | Verified for every cell over HTTP (test-only routes); real endpoints in slices 003 to 007 | Policy level in Phase 2; endpoint level in Phase 4 (test-only routes); real endpoints in slices 003 to 007 | `CompanyPolicyTest`, `SpecMatrixParityTest`, `TenantIsolationTests.everyPermissionForEveryRoleIsEnforcedOverHttp` (23 permissions x 4 roles = 92 requests), `whilePendingDeletionOnlyTheOwnerReadsAndNobodyWritesExceptTheCancellation` |
| AC-TEN-11 | Verified | Phase 3 | `CompanyApiTests.aDisabledAccountCannotCreateACompany` |
| AC-TEN-12 | Pending | Not in 002: needs plans (slice 006); matrix cells tested in Phase 2 | |
| AC-TEN-13 | Verified | Phase 3 | `CompanyApiTests.theSixthAttemptFromOneAddressWithinAnHourIsThrottledAndInvalidAttemptsCount`, `CompanyFailClosedTests` (503 when the store is down) |
| AC-TEN-14 | Pending | Not in 002: needs export and plans (slice 006, D-73); matrix cells tested in Phase 2 | |
| AC-TEN-15 | Verified | Phase 3 | `CompanyApiTests.theEleventhAttemptByOneAccountWithinADayIsThrottled` |

## Checks not run
Phase 4: `./mvnw verify` ran (325 tests, 0 failures, 2 skipped). Mutation check: removing the per-account rate-limit hit makes `parallelCreationsByOneAccountRespectTheDailyLimitExactly` fail (25 creations instead of 10). Contract and frontend checks were last run in Phase 3 and nothing in them changed. Not run: frontend unit tests, lint and build, Playwright E2E, CI jobs. No BFF route or UI exists for these endpoints (D-120).

## Phase 2 evidence (not tied to one AC)
- 404 vs 403 (D-108), role loaded per request, suspension isolated per company, pending-deletion reads: `CompanyAuthorizationTests` (9; contributes to AC-TEN-03, 04, 09, which stay Pending until Phase 4).
- Repository scope (D-110): `TenantRepositoryScopeTest`.
- Denial audit and no audit for 404 probes (D-111): `CompanyAuthorizationTests`.

## Phase 3 evidence (not tied to one AC)
- Listing and detail (`GET /api/v1/companies`, `GET /api/v1/companies/{companyId}`): `CompanyApiTests` covers own companies only, suspended and removed excluded, pending-deletion companies listed only for the Owner, 404 for a stranger and an unknown company with the same body, 403 plus a denial audit row for an Agent on a pending-deletion company, and no internal fields in responses.
- Name rule (D-105): `CompanyNameTest`, `CompanyApiTests.invalidNamesAreRejectedWithAFieldErrorAndNothingIsCreated`, `theNameIsNormalizedAndNamesAreNotUnique`.
- Known gap: an unknown field or malformed JSON is rejected while parsing, before the rate limits count the attempt (D-107 says "before validation"; parsing precedes the controller).

## Phase 4 evidence (not tied to one AC)
- Concurrency on PostgreSQL (`TenantConcurrencyTests`): 25 parallel creations by one account give exactly 10 creations and 15 throttled; two parallel ownership transfers leave exactly one Owner (one commits, one is refused); four parallel demotions of the Owner all fail and the Owner stays; four parallel inserts of a second Owner all fail.
- The transfer test relies on the unique Owner index and the Owner trigger alone. Slices 003 and 005 add the company-row lock and must repeat the test through their services.
- Test-only data: Flyway location `classpath:db/testmigration` (test profile only) and the `testrecords` package in the test source tree. `TenantRepositoryScopeTest` also covers those repositories.
