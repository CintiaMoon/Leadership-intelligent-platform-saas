# SPEC-MEM Traceability (implementation status)

- Updated: 2026-10-23, after the Phase 5 procedure and test. All AC-MEM criteria have tests; AC-MEM-17 is verified as a mechanism and closes in slice 006. Independent reviews and closure follow.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-123 to D-140.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-MEM-01 | Verified | Phase 2, 3 | `MembershipChangesTest` (4,800 combinations); `MemberApiTests.anAgentAndAViewerCannotChangeRoles`, `everyActorAndTargetRoleForSuspendAndRemoveMatchesTheMatrixOverHttp` (4 actors x 3 targets x suspend, reactivate, remove), `anOwnerAndAnAdminSeeTheMembersAndAnAgentAndAViewerDoNot` |
| AC-MEM-02 | Verified | Phase 2, 3 | `MembershipChangesTest` (4,800 combinations); `MemberApiTests.anAdminChangesAnAgentToAViewerButCannotMakeAnAdminOrTouchAnAdminOrTheOwner`, `theOwnerMembershipCannotBeChangedAndOwnerCannotBeAssigned` |
| AC-MEM-03 | Verified | Phase 4 | `MemberConcurrencyTests.parallelAttemptsOnTheOwnerByTheOwnerAndByAnAdminAllFailAndNothingChanges` (28 parallel attempts on the Owner by the Owner and an Admin: all 409 or 403, the Owner row unchanged and unaudited), `MembershipChangesTest.ac03AndAc09...`, slice 002 `TenantSchemaTests` (the database refuses an Owner-less company) |
| AC-MEM-04 | Verified | Phase 3, 4 | `MemberApiTests.suspendingDeniesTheMemberOnTheNextRequestKeepsTheRoleAndRecordsWhoAndWhen`, `removingEndsAccessOnTheNextRequestAndOnlyAnOwnerRemovesAnAdmin` (same session, next request is 404); Phase 4 repeats it under concurrency |
| AC-MEM-05 | Verified (slice 002) | Slice 002 | `TenantSchemaTests` (partial unique index on live memberships); cross-referenced |
| AC-MEM-06 | Verified | Phase 1, 3 | `MemberApiTests.auditRowsHoldIdentifiersAndEnumValuesOnly` (no email, name, password or token in any membership audit row; before/after are enum names), `MembershipSchemaV12Tests` |
| AC-MEM-07 | Verified | Phase 3 | `MemberApiTests.aMemberLeavesLosesAccessKeepsTheAccountAndGetsANewSessionIdentifier` (access ends, account kept, other companies unaffected, recorded as done by the member), `anAdminAnAgentAndAViewerCanLeaveButTheOwnerCannot` |
| AC-MEM-08 | Verified | Phase 4 | `MemberConcurrencyTests.aRoleChangeRacingARemovalOfTheSameMemberLeavesOneConsistentState` (12 rounds), `twoRoleChangesOfOneMemberLeaveTheRoleTheLastAuditRowSays`, `theSameChangeRequestedManyTimesAtOnceHappensExactlyOnce` (12 parallel suspends, reactivations and removals: one winner each), `aMemberLeavingWhileAnAdminSuspendsThemEndsInExactlyOneOfTwoStates` |
| AC-MEM-09 | Verified | Phase 3 | `MemberApiTests.anAdminAnAgentAndAViewerCanLeaveButTheOwnerCannot` (Owner gets 403, membership unchanged), `MembershipChangesTest.ac03AndAc09TheOwnerIsNeverChangedAndCannotLeave` |
| AC-MEM-10 | Verified | Phase 2, 3 | `MembershipChangesTest.ac10TheRoleAssignmentTable`; `MemberApiTests` (Owner assigns Admin/Agent/Viewer, Admin only Agent/Viewer, Owner refused with 400) |
| AC-MEM-11 | Verified | Phase 2, 3 | `MembershipChangesTest.ac11...`; `MemberApiTests.promotingToAdminNeedsARecentLoginAndOtherChangesDoNot`, `removingEndsAccessOnTheNextRequestAndOnlyAnOwnerRemovesAnAdmin` |
| AC-MEM-12 | Verified | Phase 2, 3 | `MembershipChangesTest.ac12NobodyChangesTheirOwnRole`; `MemberApiTests` (an Admin changing their own role gets 403, the Owner 409) |
| AC-MEM-13 | Verified | Phase 1, 3 | `MemberApiTests.suspendingDeniesTheMemberOnTheNextRequestKeepsTheRoleAndRecordsWhoAndWhen`, `reactivatingRestoresAccessWithThePreservedRoleAndRecordsTheActor` (status_changed_by and status_changed_at, audit before/after) |
| AC-MEM-14 | Verified (slice 001); cross-reference | Slice 001, Phase 3, 5 | `MemberApiTests.aDisabledAccountLosesItsSessionsEverywhere`; `OwnerAccountDisableProcedureTests` (sessions deleted, old cookie 401); slice 001 `LoginTests.anExistingSessionDiesWhenTheAccountIsDisabled` |
| AC-MEM-15 | Verified | Phase 2, 3 | `MembershipChangesTest.ac15...`; `MemberApiTests.everyActorAndTargetRoleForSuspendAndRemoveMatchesTheMatrixOverHttp` |
| AC-MEM-16 | Verified | Phase 4 | `MemberConcurrencyTests.aStormOfMixedChangesEndsInStatesThatTheAuditTrailExplains` (3 x 90 mixed requests; exactly one Owner; every membership's final role and status equals the replay of its audited changes; one audit row per success), `memberChangesDuringAnOwnershipTransferNeverLeaveTwoOwnersOrNone`, `aRoleChangeRacingARemoval...` (one active Owner after every round) |
| AC-MEM-17 | Mechanism verified; closed in slice 006 | Not in 003: hook in Phase 2, closed in slice 006 | `ReactivationLimitHookTests` (a full company refuses reactivation with 409 and the member stays suspended; the default allows everything until plans exist) |
| AC-MEM-18 | Verified | Phase 5 | `OwnerAccountDisableProcedureTests` (runs the runbook SQL against PostgreSQL: Owner membership suspended and still the only Owner, company flagged, Owner-only permissions unavailable to everyone else, Admin/Agent/Viewer unaffected, idempotent, unknown address changes nothing; mutation check: not suspending the Owner fails it) |

Also closed by this slice, for the consumer it supplies: AC-AUTH-14 for promoting or demoting an Admin (`MemberApiTests.promotingToAdminNeedsARecentLoginAndOtherChangesDoNot`; it stays open for the other four operations), AC-AUTH-23 for leaving (`MemberApiTests.aMemberLeavesLosesAccess...`: the old identifier stops working, the rotated cookie works), and AC-TEN-08 for memberships (D-136: `MemberApiTests.aMembershipIdFromAnotherCompanyIsRejectedForEveryRouteAndChangesNothing`).


## Phase 1 evidence (not tied to one AC)
- Migration `V12__membership_status_audit.sql`: additive and nullable; the audit CHECKs are added `NOT VALID` and then validated to keep the lock short. `MigrationV12OnExistingDataTest` applies V1 to V11, inserts a user, company, Owner membership and audit row, then V12: the rows survive, the new columns are empty, Flyway validates, and the CHECKs are validated.
- Lock order (D-125): `CompanyLocks` locks the company row, then one membership of that company, inside the caller's transaction (it refuses to run without one). `CompanyLocksTests`: a second transaction fails after the 500 ms test timeout (5 s in production) with a locking exception and changes nothing; the timeout is `SET LOCAL` and does not leak to the next user of the connection; another company's membership looks missing; a held lock is reported as 503 with `Retry-After`. Mutation check: removing the `SET LOCAL` fails both timeout tests.
- `TenantRepositoryScopeTest` still passes with the new locked methods (`lockById` on the company root, `lockByCompanyIdAndId` on memberships).

## Checks run
`./mvnw verify` ran 342 tests with 0 failures and 2 skipped. Two earlier full runs failed in tests this phase did not touch (`LoginTests`, `CompanyAuthorizationTests`); both reported elapsed times of thousands of seconds, which points to the machine suspending during the run, and the same tests passed on re-run. Not run: contract lint and the web checks (no contract or frontend change), Playwright E2E, CI jobs.

## Phase 2 evidence (not tied to one AC)
- `MembershipChanges` (pure rules) and `MembershipChangeAuthorizer` (the lock sequence of D-125): an unlocked check that the caller is an ACTIVE member, then the company lock, the target lock and a fresh load of the caller, then the verdict.
- `MembershipChangeAuthorizerTests` (13, PostgreSQL): an outsider is answered at once while another transaction holds the company lock (no queueing); a member waiting for the lock times out with a locking exception; an actor suspended or demoted while waiting is judged by the new state; a target changed while waiting is judged by its new state; a membership of another company looks missing; the authorizer refuses to run outside a transaction. Mutation check: loading the caller's entity before the lock (instead of counting) makes the demoted-while-waiting test fail, because the stale role would be used.
- `RecentAuthentication` (D-127): window ends 10 minutes after login and includes its last instant (`RecentAuthenticationTest`); `RecentAuthenticationAuthorizationManager` now uses it (the slice 001 tests still pass). Over HTTP: promoting to Admin with a stale login gets 403 `REAUTHENTICATION_REQUIRED`; other role changes and removing do not need it (`MembershipChangeHttpTests`).
- `CONFLICT` (409) added to `ErrorCode`, the OpenAPI enum and `schema.d.ts` (D-133). `ActiveMemberLimit` hook with an allow-all default (D-134).

## Checks run (Phase 2)
`./mvnw verify` ran 371 tests with 0 failures and 2 skipped; `npm run lint` in `contracts` passed (2 warnings, not examined); `gen:api` regenerated `schema.d.ts` (one line: the error-code enum); `npm run typecheck` in `apps/web` printed no errors. Not run: Playwright E2E, CI jobs. The three membership operations that write (suspend, reactivate, remove, leave, role change) do not exist yet.

## Phase 3 evidence (not tied to one AC)
- Endpoints (contract first): `GET /members`, `PATCH /members/{id}`, `POST /members/{id}/suspend`, `POST /members/{id}/reactivate`, `DELETE /members/{id}`, `POST /leave`, all under `/api/v1/companies/{companyId}`. `contracts/openapi.yaml`, `schema.d.ts` and the Postman collection are aligned.
- Each change is one transaction: authorize under the locks, write, audit. The session identifier is rotated after the leave transaction commits.
- Paths accept only canonical UUIDs (`PathIds`); anything else is 404.
- Mutation checks: removing the session rotation fails the leave test; removing the audit write fails the suspend and audit-hygiene tests.

## Checks run (Phase 3)
`./mvnw verify` ran 394 tests with 0 failures and 2 skipped; `npm run lint` in `contracts` passed (2 warnings, not examined); `gen:api` regenerated `schema.d.ts`; `npm run typecheck` in `apps/web` printed no errors. Not run: Playwright E2E, CI jobs. There is no BFF route or UI for these endpoints (D-138).

## Phase 4 evidence (not tied to one AC)
- Concurrency tests run through the real endpoints with the production lock timeout (5 s). Mutation check: replacing the company-row lock with a plain read makes the leave-versus-suspend race test fail (two different rows are involved: the caller's own membership and the target).
- **Finding fixed in this phase (D-125 note):** a refused request wrote its denial-audit row in a second transaction while still holding the company lock. In the 90-request storm the connection pool of 10 was exhausted (active 10, waiting 70, 30 s connection timeouts) and the error handler itself failed. The audit row is now written by the exception handler after the service's transaction has ended. The first run of the Owner-untouchable test, before the fix, returned 503 for most requests for the same reason (lock timeouts while connections were held).
- Deviation from the plan: the plan said to repeat the slice 002 ownership race "through the service". No ownership-transfer service exists until slice 005, so the test performs the transfer the way slice 005 will (lock the company row, demote the Owner, promote the successor, commit) in a raw transaction while the member endpoints run against it. Slice 005 must repeat it through its own service.
- Cross-tenant membership IDs and same-session revocation were already covered in Phase 3 (`MemberApiTests`).

## Checks run (Phase 4)
`./mvnw verify` ran 401 tests with 0 failures and 2 skipped. The contract and frontend did not change in this phase and were not re-run. Not run: Playwright E2E, CI jobs.
