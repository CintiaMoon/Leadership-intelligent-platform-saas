# SPEC-OWN Traceability (implementation status)

- Updated: 2026-10-25, after Phase 1 (schema and persistence).
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL); for the frontend, in the web unit tests and Playwright.
- Plan: `plan.md` (approved 2026-10-25, Q-01 to Q-19, D-176 to D-194). Spec approved the same day with AC-OWN-18 to AC-OWN-23 added.
- Phases: 0 and 1 done (this file); 2 (service, sweep, notifications), 3 (contract and API), 4 (operator transfer), 5 (frontend), 6 (review and closure) not started.

| AC | Status | Planned | Evidence so far |
|---|---|---|---|
| AC-OWN-01 | Pending | Phases 2, 3 | |
| AC-OWN-02 | Pending | Phases 2, 3 | |
| AC-OWN-03 | Pending | Phase 2 | The terminal states cannot change again at the entity level (`everyWayToEndATransferWritesAValidRowAndAFinishedTransferCannotChangeAgain`); the service test comes in Phase 2 |
| AC-OWN-04 | Pending | Phase 2 | Database side: one pending row per company under 8 simultaneous inserts (`simultaneousInitiationsLeaveExactlyOnePendingTransfer`) |
| AC-OWN-05 | Partly | Phase 2 | Database side: demote then promote in one transaction leaves exactly one Owner; promote first is refused; a demotion alone cannot commit (`OwnershipTransferSchemaTests`, three tests, with the existing V10 index and trigger). The service-level proof comes in Phase 2 |
| AC-OWN-06 | Pending | Phase 2 | |
| AC-OWN-07 | Pending | Phases 2, 3 | |
| AC-OWN-08 | Pending | Phase 2 | |
| AC-OWN-09 | Pending | Phase 2; second half in slice 007 | |
| AC-OWN-10 | Partly | Phase 2 | Database side as for AC-OWN-04 (`aSecondPendingTransferForTheSameCompanyIsRefusedButAnotherCompanyOrAFinishedOneIsFine`); the refused second initiation through the service comes in Phase 2 |
| AC-OWN-11 | Pending | Phases 2, 3 | |
| AC-OWN-12 | Pending | Phases 2, 3 | |
| AC-OWN-13 | Pending | Phase 2 | |
| AC-OWN-14 | Partly | Phase 2 | The entity treats a transfer as overdue exactly at its 7-day expiry and not before (`aTransferIsOverdueExactlyAtItsExpiryAndReadsAsExpiredBeforeTheSweep`, `anInitiatedTransferIsPendingForSevenDaysAndRoundTripsThroughTheRepository`) |
| AC-OWN-15 | Pending | Phase 2 | |
| AC-OWN-16 | Partly | Phase 2 | Reading an overdue pending transfer as EXPIRED without writing is the entity's `effectiveStatus` (same test as AC-OWN-14); the rejection of acceptance and the sweep come in Phase 2 |
| AC-OWN-17 | Pending | Phases 2, 4 | |
| AC-OWN-18 | Pending | Phase 4 | |
| AC-OWN-19 | Pending | Phase 2 | |
| AC-OWN-20 | Pending | Phase 2 | |
| AC-OWN-21 | Pending | Phase 2 | |
| AC-OWN-22 | Partly | Phases 2, 3 | A transfer of another company is not found by ID or by lock (`aTransferOfAnotherCompanyIsNotFoundByIdOrByLock`); the "only the parties can read" rule comes in Phases 2 and 3 |
| AC-OWN-23 | Pending | Phase 2 | |

## Phase 0 and 1 evidence (not tied to one AC)
- Phase 0: D-176 to D-194 are Approved in the register; SPEC-OWN is Approved with AC-OWN-18 to AC-OWN-23 and its traceability table updated; SPEC-TEN's matrix has the "Accept ownership transfer" row. **Deviation from the plan:** the plan put the matrix row and its parity test in Phase 3. `SpecMatrixParityTest` ties the spec's table to the `Permission` enum, so adding the row to the spec requires `Permission.ACCEPT_OWNERSHIP_TRANSFER` and its `CompanyPolicy` row in the same change; both are here, with a test route in `PermissionRoutesController` (one route per permission). The permission is not used by any endpoint yet.
- `V14__create_ownership_transfer.sql` (new table only): foreign keys to the company and the users; CHECKs for the status, initiator different from recipient, expiry after creation, the resolution matching the status, and who resolved it (the recipient accepts, the initiator cancels, nobody expires it or cancels it for the operator transfer); the partial unique index for one pending transfer per company; indexes for the company history, the recipient's pending transfers and the sweep.
- `OwnershipTransfer` (entity with the state machine and the overdue rules), `TransferStatus`, `TransferResolution`, `OwnershipTransferRepository` (extends `Repository`; every query names the company; `TenantRepositoryScopeTest` stays green), `OwnershipTransferLocks` (company row first, then the transfer; `MANDATORY` propagation; another company's ID is "not found").
- Mutation checks on the migration, all caught and restored: removing the partial unique index fails the two uniqueness tests; removing the self-transfer check fails `ownershipCannotBeHandedToOneself`; removing the resolver rule fails `onlyTheRecipientAcceptsAndOnlyTheInitiatorCancelsAndNobodyExpiresIt`.

## Checks run (Phase 1)
- `OwnershipTransferSchemaTests`: 17 tests, 0 failures. `MigrationV14OnExistingDataTest`: passed (V1 to V13 data with a company, memberships and an invitation is kept, the new table starts empty, every constraint is validated, the unique index and all five indexes exist).
- `./mvnw verify` in `services/api`: 538 tests (520 before this phase), 0 failures, 0 errors, 2 skipped (the same two as before), BUILD SUCCESS. This includes `SpecMatrixParityTest`, `CompanyPolicyTest`, `TenantRepositoryScopeTest` and `TenantIsolationTests` with the new permission.
- Not run: the web build, Playwright and contract checks (no contract or frontend change in this phase), the CI jobs.
