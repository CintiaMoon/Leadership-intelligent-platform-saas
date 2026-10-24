# SPEC-MEM Traceability (implementation status)

- Updated: 2026-10-23, after the Phase 5 reviews and fixes. All AC-MEM criteria have tests; AC-MEM-17 is a mechanism (closes in slice 006) and AC-MEM-18 leaves the deletion guard to slice 007. The slice was closed by the user on 2026-10-23 (see the closure status).
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-123 to D-140.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-MEM-01 | Verified | Phase 2, 3 | `MembershipChangesTest` (4,800 combinations); `MemberApiTests.anAgentAndAViewerCannotChangeRoles`, `everyActorAndTargetRoleForSuspendAndRemoveMatchesTheMatrixOverHttp` (4 actors x 3 targets x suspend, reactivate, remove), `anOwnerAndAnAdminSeeTheMembersAndAnAgentAndAViewerDoNot` |
| AC-MEM-02 | Verified | Phase 2, 3 | `MembershipChangesTest` (4,800 combinations); `MemberApiTests.anAdminChangesAnAgentToAViewerButCannotMakeAnAdminOrTouchAnAdminOrTheOwner`, `theOwnerMembershipCannotBeChangedAndOwnerCannotBeAssigned` |
| AC-MEM-03 | Verified (database guarantee and deterministic API tests) | Phase 4 | `MemberConcurrencyTests.parallelAttemptsOnTheOwnerByTheOwnerAndByAnAdminAllFailAndNothingChanges` (28 parallel attempts, all 409 or 403; the outcomes are deterministic, so this is not a race on the Owner row, which no API path can reach), `MembershipChangesTest.ac03AndAc09...`, slice 002 `TenantSchemaTests` (the database refuses an Owner-less company and a second Owner: this is the real guarantee under concurrency) |
| AC-MEM-04 | Verified | Phase 3 | `MemberApiTests.suspendingDeniesTheMemberOnTheNextRequestKeepsTheRoleAndRecordsWhoAndWhen`, `removingEndsAccessOnTheNextRequestAndOnlyAnOwnerRemovesAnAdmin` (same session, next request is 404). No concurrency test exercises this criterion |
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
| AC-MEM-18 | Verified for the data state and the operator procedure; deletion guard is slice 007's | Phase 5 | `OwnerAccountDisableProcedureTests` (runs the runbook SQL against PostgreSQL): Owner membership suspended and still the only Owner; company flagged; the Owner gets no access even after the account is re-enabled and nobody can reactivate the membership; other members unaffected; idempotent; an unknown address or a bad ticket stops the script and commits nothing; mutation checks: not suspending the Owner fails it, removing the account guard fails the unknown-address test. **Not proven here:** that company deletion is rejected (no deletion endpoint exists) and nothing reads `company.ownership_resolution_at` yet. Slice 007 must refuse deletion of a flagged company |

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

## Phase 5: independent reviews (2026-10-23)
Two read-only agents reviewed the merged slice and the operator procedure: a security engineer and a code reviewer. Neither found a High issue. Neither ran code.

| Finding | Source | Disposition |
|---|---|---|
| Any ACTIVE member (an Agent or Viewer) could take the company lock before being refused, so a loop of refused requests could make Owners' and Admins' changes time out and starve the connection pool | Security M1, Code M1 | Fixed: the caller's role is read as a plain value before any lock; roles that can never do the operation (Agent and Viewer for changes, the Owner for leaving) are refused without locking. `MembershipChangeAuthorizerTests.anAgentAndAViewerAreRefusedWithoutQueueingForTheLockAndSoIsTheOwnerLeaving` (mutation check: removing the early refusal makes it fail). D-137 corrected. No rate limit added for Owners and Admins |
| Operator SQL: audit rows unattributable, a mistyped address looked like success, email not normalized, no lock timeout, account updated before the company lock | Security M2, Code L2 | Fixed: required `ticket` (validated) recorded with the database role in every audit row; `lower(btrim())` on the address; the script stops with an error when the account does not exist; `SET LOCAL lock_timeout = '5s'`; company rows locked before the account is updated. While fixing it I found my first guard (`CASE ... ELSE 1 / 0`) was folded by the planner and failed every run, which the negative tests had hidden by passing for the wrong reason; the positive tests exposed it. Now `1 / count(*)` and `1 / (CASE ...)`. `OwnerAccountDisableProcedureTests` (7) |
| The validation `role = OWNER` or missing was judged before the permission, so a Viewer's attempt to assign Owner got 400 and no denial record | Security L1, Code L1 | Fixed: permission first, then the request; a missing role is `REQUIRED`. `MemberApiTests.anAttemptToAssignOwnerByAMemberWithNoRightsIsRefusedAndRecordedAsADenial`, the oracle in `MembershipChangesTest` |
| V12 comment claims the lock stays short; it does not (one transaction) | Code M2 | Documented, not changed: `V12` is merged and applied, and editing it would change its Flyway checksum. D-129 note |
| AC-MEM-18 was marked Verified on circular evidence; the flag is never read; deletion rejection is unproven | Code M3 | Corrected as above; added the re-enabled-Owner test. Carried forward: slice 007 must refuse deletion of a flagged company |
| Traceability claimed results not recorded (Phase 5 run, AC-MEM-04 under concurrency, AC-MEM-03 as a race) | Code M4 | Corrected: this section, the AC-MEM-03, 04 and 18 rows, and the checks below |
| Test names claimed more than they tested (leave); pending-deletion company state not covered over HTTP; timing assertion; NULL-vacuous regex | Code L3, L4, L5 | Fixed: Agent and suspended-member leave cases, `whileACompanyIsPendingDeletionNoMemberChangeIsAllowedToAnyone`, timing assertion replaced by an outcome assertion, regex made NULL-safe. Not changed: the storm test orders audit rows by timestamp and then a random ID, so a same-microsecond tie could theoretically flake it |
| Stale comments and contract text (manager Javadoc, ErrorCode description, a 409 on leave that cannot happen), unused user lookup on remove and leave | Code L6, Info | Fixed |
| The throttle slot of the denial audit is consumed before the insert, so a failed insert hides that denial for an hour; REAUTHENTICATION_REQUIRED is not audited | Security L1 | Accepted: both are logged (`log.error`) and rare; no new requirement invented |
| Non-Owner memberships of a disabled account stay ACTIVE | Security L3 | Decided (D-141, 2026-10-24): leave ACTIVE |
| A re-enabled Owner stays suspended and cannot be reactivated | Security L4 | Partly decided (D-142, 2026-10-24): reversal script written and tested; the rest blocks the slice 005 runbook |
| Owner leaving is refused through the matrix cell only; the `self` check in the rules is unreachable | Security L2 | Accepted: the early refusal now covers the Owner leaving explicitly, and the self check stays as a safeguard |

## Carried forward to later slices
- Slice 005: repeat the ownership-transfer race through its own service (Phase 4 used a raw transaction); write and rehearse the operator transfer runbook (D-142); the transfer must lock the company row first.
- Slice 006: replace `UnlimitedActiveMembers` with the plan-based limit (AC-MEM-17); an ACTIVE membership of a DISABLED account uses a plan seat (D-141), so count it.
- Slice 007: refuse company deletion requests while `ownership_resolution_at` is set (AC-MEM-18, AC-DEL-20) and do not purge a flagged company (D-143, which amends SPEC-DEL; change the spec text in the slice 007 plan).
- Platform: still no server-wide request body limit and no measured connection-pool headroom (the Phase 4 storm showed that a burst of 90 simultaneous requests is survivable only because the denial audit no longer needs a second connection).

## Checks run (Phase 5)
`./mvnw verify` ran 413 tests with 0 failures and 2 skipped after the fixes; `npm run lint` in `contracts` passed (2 warnings, not examined); `gen:api` regenerated `schema.d.ts` (description text and the removed 409 on leave); `npm run typecheck` in `apps/web` printed no errors. The contract changed (ErrorCode description, no 409 on leave): `npm run lint` in `contracts`, `gen:api` and the `apps/web` typecheck were re-run. Not run: Playwright E2E, CI jobs, the operator script in a deployed environment or with `psql` (the test executes the same statements through JDBC, with the two variables substituted, and skips the `psql`-only usage lines).

## Slice 003 closure status (2026-10-23)
All acceptance criteria that belong to this slice have tests. Exceptions: AC-MEM-17 is a mechanism (the plan-based limit arrives in slice 006) and AC-MEM-18 leaves the deletion guard to slice 007. AC-AUTH-14 (for promoting or demoting an Admin), AC-AUTH-23 (for leaving) and AC-TEN-08 (for memberships) are closed for this consumer.

**The user confirmed the closure sign-off on 2026-10-23** ("1. confirmed"), including the accepted-risk list below. D-141 and D-142 stay open. Each accepted risk stays a gate for the point stated in its row; nothing here claims it was checked.

| Open item | Status | Evidence / what is still owed |
|---|---|---|
| Independent security and code reviews | Done | Findings and dispositions are in the Phase 5 section |
| D-137 (no rate limit for Owners and Admins) | Accepted risk (signed off 2026-10-23) | Agents and Viewers are refused before any lock; Owners' and Admins' changes are audited and serialized but not throttled |
| D-141 memberships of a disabled account | Done | Decided 2026-10-24: leave ACTIVE, reversible, a plan seat is used (slice 006 must count them) |
| D-142 recovery of a re-enabled Owner | Decided (2026-10-24) | Reversal script written and tested (`OwnerAccountReversalProcedureTests`, 6, with mutation checks on its two safety conditions); the operator transfer is designed in `operator-ownership-transfer.md` and built in slice 005; a company with no other member stays flagged and retained (`list-flagged-companies.sql`, `FlaggedCompaniesQueryTests`); the production gate is in the disable runbook |
| Operator procedure in a deployed environment, with `psql` | Accepted risk (signed off 2026-10-23) | The test runs the same statements through JDBC; the runbook stays Draft until one rehearsal is recorded. Owed: before the procedure is used in production |
| V12 lock behaviour on a large `audit_event` table | Accepted risk (signed off 2026-10-23) | The migration comment overstates; apply in a quiet period (D-129 note) |
| Deletion guard on `ownership_resolution_at` (AC-MEM-18) | Carried forward | Slice 007 |
| Plan-based active-member limit (AC-MEM-17) | Carried forward | Slice 006 |
| Ownership-transfer race through its own service; operator transfer runbook | Carried forward | Slice 005 |
| Frontend unit tests, lint and build, Playwright E2E, CI results of the merged pull requests | Accepted risk (signed off 2026-10-23) | Not run by the assistant for this slice; there is no UI (D-138). CI results of the merged pull requests were not read |
| Connection-pool headroom, request body size limit | Accepted risk (signed off 2026-10-23) | Not measured or configured (platform decisions) |
| Storm test audit ordering by timestamp then random ID | Accepted risk (signed off 2026-10-23) | A same-microsecond tie could flake it in theory |
| Closure sign-off | Done | Slice 003 closed by the user on 2026-10-23, with D-141 and D-142 still open |

## Follow-up (2026-10-24): D-141 and D-142
- D-141 decided (leave ACTIVE; reversible; a plan seat is used). No code change.
- D-142 item 1 decided and built: `reverse-account-disable.sql` restores the Owner membership and clears the flag only when the membership is still the Owner role, is SUSPENDED in a flagged company, and the audit trail holds the operator suspension for it. `OwnerAccountReversalProcedureTests` (6): full round trip including the Owner working again after logging in; idempotent; after a (simulated) ownership transfer nothing is restored and the company stays flagged; an Owner suspended by hand is never restored; an unknown address or a bad ticket commits nothing; an account that owns nothing is only enabled. Mutation checks: removing the audit-trail condition and removing the Owner-role condition each make a test fail. New audit event `company.ownership_resolution.cleared` added to the catalogue.
- Not run: the script with real `psql` or in a deployed environment. `./mvnw verify` ran 420 tests with 0 failures and 2 skipped after the last follow-up (D-142 items 2 to 4 added the flagged-companies query test).

- D-142 items 2 to 4 decided on 2026-10-24: design of the operator transfer recorded in `operator-ownership-transfer.md` (to be built and rehearsed in slice 005); a company whose Owner was its only member stays flagged and retained, listed by `docs/runbooks/operations/sql/list-flagged-companies.sql` (`FlaggedCompaniesQueryTests` runs it against PostgreSQL); production gate (routine use waits for the rehearsals, emergency use with the security owner's recorded approval) written into the disable runbook.
- D-143 (new, decided 2026-10-24): a flagged company must not be purged. Found while answering D-142: a deletion pending when the Owner is disabled could not be cancelled by anyone. Amends SPEC-DEL; slice 007 carries it.
