# SPEC-TEN Traceability (implementation status)

- Updated: 2026-10-21, after Phase 5 (reviews and fixes). Verified: AC-TEN-01, 02, 03, 04, 05, 07, 09, 10 (every cell over HTTP), 11, 13, 15. AC-TEN-08 is verified as a mechanism and closed in slice 003. AC-TEN-06, 12, 14 belong to later slices. Closure waits on the two Open decisions below (D-121, D-122) and the approval of this report.
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
| AC-TEN-07 | Verified | Phase 3 | `CompanyApiTests.anAccountThatIsNoLongerVerifiedCannotCreateACompany`: a GET (no CSRF check) returns exactly 401 `UNAUTHENTICATED` once the account is unverified, so the gate itself is proven; the POST is refused (401 or 403) and creates nothing |
| AC-TEN-08 | Mechanism verified; closed in slice 003 | Mechanism in Phase 4 (Q-14); closed in slice 003 | `TenantIsolationTests.aRelatedRecordFromAnotherCompanyIsRejectedAndNothingChanges`, `theDatabaseAlsoRefusesANoteThatPointsAtAnotherCompanysRecord` (test-only tables `test_record` and `test_note`, composite foreign key) |
| AC-TEN-09 | Verified | Phase 4 | `TenantIsolationTests.suspendingOrRemovingAMembershipInOneCompanyLeavesTheOthersUntouched`, `aRoleInOneCompanyGivesNothingInAnother`, `CompanyAuthorizationTests.oneCompanysSuspensionDoesNotAffectAnother` |
| AC-TEN-10 | Verified for every cell over HTTP (test-only routes); real endpoints in slices 003 to 007 | Policy level in Phase 2; endpoint level in Phase 4 and 5 (test-only routes); real endpoints in slices 003 to 007 | `SpecMatrixParityTest` ties every cell of `CompanyPolicy` to the spec table (the only link to the spec); `TenantIsolationTests.everyPermissionForEveryRoleIsEnforcedOverHttp` (23 x 4 requests; expected status comes from `CompanyPolicy`, so it proves wiring, not the table); `anAdminActsOnAgentsAndViewersOnlyAndTheOwnerOnAnyoneOverHttp` (literal expectations for the three "Agent/Viewer only" rows through the service with a target role; mutation check: letting an Admin act on an Admin fails it); `whilePendingDeletionOnlyTheOwnerReadsAndNobodyWritesExceptTheCancellation` |
| AC-TEN-11 | Verified | Phase 3 | `CompanyApiTests.aDisabledAccountCannotCreateACompany`: same shape as AC-TEN-07 |
| AC-TEN-12 | Pending | Not in 002: needs plans (slice 006); matrix cells tested in Phase 2 | |
| AC-TEN-13 | Verified | Phase 3 | `CompanyApiTests.theSixthAttemptFromOneAddressWithinAnHourIsThrottledAndInvalidAttemptsCount`, `CompanyFailClosedTests` (503 when the store is down) |
| AC-TEN-14 | Pending | Not in 002: needs export and plans (slice 006, D-73); matrix cells tested in Phase 2 | |
| AC-TEN-15 | Verified | Phase 3 | `CompanyApiTests.theEleventhAttemptByOneAccountWithinADayIsThrottled` |

## Checks not run
Phase 5: `./mvnw verify` ran (326 tests, 0 failures, 2 skipped); `npm run lint` in `contracts` passed (2 warnings, not examined); `gen:api` regenerated `schema.d.ts` (description text only); `npm run typecheck` in `apps/web` printed no errors. Mutation checks: removing the per-account rate-limit hit fails the daily-limit test; letting an Admin act on an Admin fails the targeted-role test; removing the canonical-UUID check fails `aNonCanonicalUuidFormNeverReachesACompany`. Not run: frontend unit tests, lint and build, Playwright E2E, CI jobs, migration V11 on a database that already holds V10 data (the tests apply V1 to V11 to an empty database). No BFF route or UI exists for these endpoints (D-120).

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

## Phase 5: independent reviews (2026-10-21)
Two read-only agents reviewed the merged slice: a security engineer and a code reviewer. Neither found a High issue. Neither ran code.

| Finding | Source | Disposition |
|---|---|---|
| AC-TEN-10 over HTTP never supplied a target role, so the "Agent/Viewer only" cells were only tested as denied; the expected status was derived from the policy | Code M1 | Fixed: targeted test route through the service with literal expectations; claim restated in the AC-TEN-10 row |
| Two of four concurrency tests were not races; the Phase 1 two-Owner test was weak; the loser's failure was not checked | Code M2 | Fixed: both non-race tests and the Phase 1 test removed; the transfer race now asserts SQLState 23505 or 23000 |
| AC-TEN-07 and 11 tests could pass on a CSRF rejection | Code M3 | Fixed: a GET asserts exactly 401 `UNAUTHENTICATED` |
| `@Size(max = 400)` never ran; NFKC ran on any input size; `INVALID_NAME` missing from the contract | Code M4, Security 2 | Fixed: raw length checked before normalizing (`CompanyName.MAX_RAW_CHARS`), `INVALID_NAME` added to the contract. No server-wide body-size limit exists; that belongs to a platform decision |
| `CompanyRepository` exposed `findAll` and `deleteAll` | Security 4 | Fixed: narrowed to `Repository` with the four methods the slice uses |
| Owner trigger ignored a membership moving between companies | Security 5 | Fixed: migration `V11__membership_company_immutable.sql` (new file; V10 is untouched) and a test |
| `UUID.fromString` accepts shortened forms | Security 6 | Fixed: canonical 36-character form only, with a test |
| Stale decision IDs in comments and tests | Code L2 | Fixed, except the comment in the already-applied `V10` (changing it would change its Flyway checksum) |
| D-114 said "reuse the D-89 manager" but the code relies on the session filter | Code L3 | Fixed in the register (the code was right) |
| Scope test accepted any method name containing "CompanyId" | Code L5 | Fixed: stricter pattern, with negative examples |
| Denial audit can be amplified by a member repeating a forbidden request | Security 1, Code L4 | **Open: D-121, blocks slice 003.** No live route denies a role today |
| Company names accept bidi, zero-width and other format characters | Security 3, Code L1 | **Open: D-122.** Amends D-105, needs approval |
| AC-TEN-02 is guaranteed by the Owner trigger, not by `@Transactional` | Code L6 | Accepted and documented (AC-TEN-02 row) |
| Authorization and the mutation are not one transaction (TOCTOU) | Security 7, Code Info | Carried forward, see below |
| `EXPORT_DATA` is treated as a read while a deletion is pending | Code Info | Carried forward to slice 006 |

## Carried forward to later slices
- Slices 003 to 005: check the permission and perform the change in one transaction, lock the membership row and, for Owner changes, the company row (`FOR UPDATE`), and repeat the ownership-race test through the service. The deferred trigger alone gives "at least one Owner at commit".
- Slice 003: close AC-TEN-08 with real related records; replace the test-only routes with real endpoints and keep the HTTP matrix test pointed at them.
- Slice 006: confirm whether export is allowed to an Owner during pending deletion.
- Platform: a server-wide request body size limit (Tomcat/Jackson) is not configured.
- The company list is not paginated and `updated_at` is never maintained; both are fine for this slice.
