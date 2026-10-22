# SPEC-MEM Traceability (implementation status)

- Updated: 2026-10-21, after Phase 1 (schema and persistence). No acceptance criterion is verified yet; AC-MEM-06 and 13 have their schema in place.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-123 to D-140.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-MEM-01 | Pending | Phase 2, 3 | |
| AC-MEM-02 | Pending | Phase 2, 3 | |
| AC-MEM-03 | Pending | Phase 4 (on the slice 002 schema guarantees) | |
| AC-MEM-04 | Pending | Phase 3, 4 | |
| AC-MEM-05 | Pending | Slice 002 (`TenantSchemaTests`); cross-referenced | |
| AC-MEM-06 | Pending (schema ready) | Phase 1 (audit columns), Phase 3 | `MembershipSchemaV12Tests.anAuditRowCarriesBeforeAndAfterValuesThatAreEnumNamesOnly` (the database accepts enum names only for before/after); the events themselves come in Phase 3 |
| AC-MEM-07 | Pending | Phase 3 | |
| AC-MEM-08 | Pending | Phase 4 | |
| AC-MEM-09 | Pending | Phase 3 | |
| AC-MEM-10 | Pending | Phase 2, 3 | |
| AC-MEM-11 | Pending | Phase 2, 3 | |
| AC-MEM-12 | Pending | Phase 2, 3 | |
| AC-MEM-13 | Pending (schema ready) | Phase 1, 3 | `MembershipSchemaV12Tests.whoChangedTheStatusMustBeARealUser`, `theNewColumnsAreNullableSoExistingRowsAndNewMembershipsNeedNoValue`; the behaviour comes in Phase 3 |
| AC-MEM-14 | Pending | Slice 001 (enforced); cross-reference test in Phase 3 | |
| AC-MEM-15 | Pending | Phase 2, 3 | |
| AC-MEM-16 | Pending | Phase 4 | |
| AC-MEM-17 | Pending | Not in 003: hook in Phase 2, closed in slice 006 | |
| AC-MEM-18 | Pending | Phase 5 (runbook SQL and test) | |

Also closed by this slice, for the consumer it supplies: AC-AUTH-14 (promoting or demoting an Admin; it stays open for the other four operations) and AC-AUTH-23 (session rotation after a change to one's own membership), and AC-TEN-08 for memberships (D-136).


## Phase 1 evidence (not tied to one AC)
- Migration `V12__membership_status_audit.sql`: additive and nullable; the audit CHECKs are added `NOT VALID` and then validated to keep the lock short. `MigrationV12OnExistingDataTest` applies V1 to V11, inserts a user, company, Owner membership and audit row, then V12: the rows survive, the new columns are empty, Flyway validates, and the CHECKs are validated.
- Lock order (D-125): `CompanyLocks` locks the company row, then one membership of that company, inside the caller's transaction (it refuses to run without one). `CompanyLocksTests`: a second transaction fails after the 500 ms test timeout (5 s in production) with a locking exception and changes nothing; the timeout is `SET LOCAL` and does not leak to the next user of the connection; another company's membership looks missing; a held lock is reported as 503 with `Retry-After`. Mutation check: removing the `SET LOCAL` fails both timeout tests.
- `TenantRepositoryScopeTest` still passes with the new locked methods (`lockById` on the company root, `lockByCompanyIdAndId` on memberships).

## Checks run
`./mvnw verify` ran 342 tests with 0 failures and 2 skipped. Two earlier full runs failed in tests this phase did not touch (`LoginTests`, `CompanyAuthorizationTests`); both reported elapsed times of thousands of seconds, which points to the machine suspending during the run, and the same tests passed on re-run. Not run: contract lint and the web checks (no contract or frontend change), Playwright E2E, CI jobs.
