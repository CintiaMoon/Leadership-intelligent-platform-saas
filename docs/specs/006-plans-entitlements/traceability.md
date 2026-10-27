# SPEC-PLAN Traceability (implementation status)

- Updated: 2026-10-27, after Phase 1 (schema). Only the migration and its tests exist; no service, endpoint or screen.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL); for the frontend, in the web unit tests and Playwright.
- Plan: `plan.md` (approved 2026-10-27, Q-01 to Q-23, D-195 to D-217). Spec approved the same day with AC-PLAN-21 to AC-PLAN-25 and the gating, existing-companies, endpoint and operator sections added.
- Phases: 0 and 1 done; 2 (domain and service), 3 (gating, contract and API), 4 (operator assignment), 5 (frontend), 6 (review and closure) not started.

| AC | Status | Planned | Evidence so far |
|---|---|---|---|
| AC-PLAN-01 | Pending | Phases 1, 2 | |
| AC-PLAN-02 | Partly (the data) | Phases 1, 2 | `PlanSchemaTests.theThreePlansAreSeededWithTheLimitsAndFeaturesOfTheSpec` (every limit and feature of Trial, Starter and Team compared with the spec; the Trial has the full Team set). The service that reads them is Phase 2 |
| AC-PLAN-03 | Pending | Phase 3 | |
| AC-PLAN-04 | Pending | Phases 2, 3 | |
| AC-PLAN-05 | Pending (member limit only; teams and assessments stay open until the domain spec, D-68, D-206) | Phase 2, 3 | |
| AC-PLAN-06 | Not applicable: no billing state exists; the only writers are the application and the operator script | - | To be stated in the closure |
| AC-PLAN-07 | Pending | Phase 3 | |
| AC-PLAN-08 | Pending | Phase 3 | |
| AC-PLAN-09 | Partly (the database side) | Phases 1, 2 | The trial grant and the subscription are ordinary rows that Phase 2 writes in the company-creation transaction; nothing to test yet on the transaction itself |
| AC-PLAN-10 | Pending | Phase 2 | |
| AC-PLAN-11 | Pending | Phases 2, 4 | |
| AC-PLAN-12 | Pending (export has no operation yet: tested through the existing `EXPORT_DATA` test route) | Phase 3 | |
| AC-PLAN-13 | Pending | Phase 3 | |
| AC-PLAN-14 | Pending (member limit only) | Phase 3 | |
| AC-PLAN-15 | Partly (the database side) | Phases 1, 2 | `PlanSchemaTests.aUserHasOneTrialGrantAndItReferencesNoCompany` (a second grant for one user is refused), `MigrationV15OnExistingDataTest` (a user who already created a company has used the slot). The service decision is Phase 2 |
| AC-PLAN-16 | Verified at the schema (the service reading the latest version is Phase 2) | Phases 1, 2 | `PlanSchemaTests.aPlanVersionCanNeverBeChangedOnlyAddedAndARowCannotBeAddedToOneThatIsInUse`, `aLimitOrFeatureCannotBeAddedToAVersionInUseEvenForAKeyItLacks`, `aNewVersionIsAddedWithItsOwnRowsAndAnExistingSubscriptionKeepsItsVersion` (a new version is added, the existing subscription keeps version 1; the test rolls back so no version is left behind) |
| AC-PLAN-17 | Partly (the database side) | Phase 2 | `PlanSchemaTests.everyPairOfStatesIsAllowedOrRefusedExactlyAsTheSpecSays` (all 25 pairs against a table written from the spec), `aPlanChangeWhileActiveIsAllowedAndAnOperatorCanLeaveEveryStateForActive`. The `SubscriptionTransitions` class is Phase 2 |
| AC-PLAN-18 | Pending | Phase 2 | |
| AC-PLAN-19 | Pending | Phase 3 | |
| AC-PLAN-20 | Pending | Phase 3 | |
| AC-PLAN-21 | Pending | Phase 4 | |
| AC-PLAN-22 | Pending | Phase 3 | |
| AC-PLAN-23 | Verified | Phase 1 | `MigrationV15OnExistingDataTest.v15GivesEveryExistingCompanyNoPlanAndRecordsWhoHasAlreadyCreatedOne` (a database migrated to V14 with four companies, one of them pending deletion, then to V15: all four NO_PLAN with no plan and no trial, nothing lost, every constraint validated), `v15OnAnEmptyDatabaseCreatesTheCatalogAndNothingElse` |
| AC-PLAN-24 | Pending | Phase 3 | |
| AC-PLAN-25 | Partly (the database side) | Phase 2 | `PlanSchemaTests.simultaneousFirstCompanyGrantsForOneUserAdmitExactlyOne` (8 simultaneous `INSERT ... ON CONFLICT DO NOTHING` for one user, 5 rounds: exactly one row each time). The creation path that uses it is Phase 2 |
| AC-MEM-17 | Pending (closes here: the `ActiveMemberLimit` hook of slice 003) | Phases 2, 3 | |
| AC-INV-12 | Pending (closes here: the hook of slice 004) | Phases 2, 3 | |
| AC-INV-26 | Pending (closes here) | Phases 2, 3 | |
| AC-TEN-12 | Pending | Phase 3 | |
| AC-TEN-14 | Pending (through the export test route) | Phase 3 | |

## Phase 0
- D-195 to D-217 are Approved in the register (user: "Approve all as written", and for Q-02, Q-05, Q-09 and Q-12 the answers "use your proposal", "proposal accepted", and "Literal spec text" for Q-09). D-68 is Partly decided: its deferral unblocks this slice (D-196), the domain spec stays open for the team and assessment quotas and slice 007.
- SPEC-PLAN is Approved with the amendments of plan Q-21; the SPEC-TEN matrix text points to the subscription dimension.
- Decisions that shape the work: all existing companies start NO_PLAN (D-199); the spec's literal EXPIRED_LOCKED text stands, so a member cannot leave a locked company and a pending ownership transfer cannot be cancelled there (D-203); teams and assessments limits are stored and shown with `enforced: false` (D-206).
- Hand-offs: slice 007 must keep `user_trial_grant` when it purges a company (D-216); the staging rehearsal of `operator-assign-plan.sql` is the security owner's team's (D-209).

## Phase 1: schema (V15)
- `V15__create_plans_and_subscriptions.sql`: `plan`, `plan_limit`, `plan_feature` (seeded with version 1 of Trial, Starter and Team), `company_subscription` (primary key `company_id`), `user_trial_grant` (primary key `user_id`, no foreign key to the company), the backfill, and the guards. No existing table is changed.
- **Guards in the database:** unknown plan codes, limit keys and feature keys, a negative limit, a repeated (code, version) are refused; a plan version, its limits and its features cannot be updated or deleted, and nothing can be added to a version that a subscription already uses (a change is a new version); the five MVP states only (PAST_DUE and CANCELED are not allowed until a billing slice adds them); NO_PLAN has no plan and no trial; a trial and its two expired states have a plan and a start; ACTIVE has a plan; the three trial timestamps come together and are exactly 336 hours and 336 hours apart (hours, not calendar days, so no session time zone can move them: tested in three zones across a daylight-saving change); a trial uses the Trial plan and ACTIVE a paid plan (trigger); the state machine of SPEC-PLAN (trigger: TRIALING to EXPIRED_READ_ONLY to EXPIRED_LOCKED, any other state or ACTIVE itself to ACTIVE, nothing else, and a subscription never moves to another company).
- **Backfill (D-199):** every existing company gets a NO_PLAN subscription. **Derived decision, not asked:** a user who already created a company has used the first-company slot of D-45 (AC-PLAN-15), so the migration records a grant for the creator of the earliest company in the audit trail (the actor of `tenant.company.created`); a company with no such event, or one created by the system, grants nothing. Without it a user with ten existing companies would get a Trial for the eleventh. You can ask for the opposite.
- **A bug the tests found in the first draft of the migration:** a `CHECK` passes when its expression is NULL, so a subscription with a start and an end but no read-only end was accepted. The constraint now requires every timestamp explicitly (`aTrialSubscriptionNeeds...` fails without it).
- **Mutation checks (migration):** 48 mutations of `V15` (each constraint, trigger, seed value and backfill rule removed or weakened; the file restored after each). 44 were caught at once. Four were not, for reasons found and fixed in the tests: the in-use trigger for limits (every key already existed, so a primary-key collision masked it: now a version with a missing key is tested), the uniqueness of the subscription (the test only repeated the same state: now another state is tried too), the filter that keeps system events out of the grant backfill (the fixture event had no actor, so another filter hid it: the event now names a user); those three were re-run and are caught. **One is redundant and stays uncaught:** adding PAST_DUE to the list of allowed states changes nothing, because the field-consistency constraint already refuses every state it does not list; both constraints are kept so that a billing slice has to change both.
- **Not tested here:** that every company has a subscription (the database does not require it: many existing tests insert companies directly, and Phase 2 makes the application create one in the same transaction; a test of that belongs to Phase 2); the performance of the backfill on a large table (one `INSERT ... SELECT` over `company`, which is small).

## Checks run (Phase 1)
- `./mvnw verify` in `services/api`: **644 tests, 0 failures, 0 errors, 2 skipped** (the same two as before), BUILD SUCCESS. New: 17 (`PlanSchemaTests` 15, `MigrationV15OnExistingDataTest` 2).
- `PlanSchemaTests` (15) and `MigrationV15OnExistingDataTest` (2) pass on PostgreSQL 17.6; the migration was validated on an empty database and on one holding V1 to V14 data.
- Not run: web checks (no frontend or contract change), the CI jobs.
- The local build now needs network access the first time: Dependabot's Spring Boot 4.1.2 bump (PR #67) was not in my offline Maven cache.
