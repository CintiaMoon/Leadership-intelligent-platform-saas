# SPEC-INV Traceability (implementation status)

- Updated: 2026-10-24, after Phase 1 (schema and persistence). Only schema-level evidence exists; every criterion is still Pending until its service and endpoints land.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL), and for AC-INV-20 and the accept flow, in the web unit tests and Playwright.
- Plan: `plan.md`. Decisions: D-144 to D-163. Spec approved 2026-10-24 with AC-INV-27 to AC-INV-29 added.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-INV-01 | Pending | Phase 2, 3 | |
| AC-INV-02 | Pending | Phase 4 | |
| AC-INV-03 | Pending | Phase 3 (revoked), Phase 4 | |
| AC-INV-04 | Pending | Phase 4 (PostgreSQL concurrency) | |
| AC-INV-05 | Pending (schema ready) | Phase 1, 2 | `InvitationSchemaTests.aTokenHashIsLowerCaseHexOf64CharactersAndNeverStoredAsAToken`, `onlyAPendingInvitationCanHoldATokenAndTheOutcomeFieldsMatchTheStatus` (the table can hold a hash only, and only while pending); minting at delivery comes in Phase 2 |
| AC-INV-06 | Pending | Phase 3, 4 | |
| AC-INV-07 | Pending | Phase 5 | |
| AC-INV-08 | Pending | Phase 4 | |
| AC-INV-09 | Pending | Phase 4 | |
| AC-INV-10 | Pending | Phase 4 | |
| AC-INV-11 | Pending | Phase 2, 4 | |
| AC-INV-12 | Pending | Mechanism in Phase 4; closed in slice 006 | |
| AC-INV-13 | Pending | Phase 3 | |
| AC-INV-14 | Pending | Phase 4 | |
| AC-INV-15 | Pending (schema ready) | Phase 1, 2, 3 | `InvitationSchemaTests.atMostOnePendingInvitationExistsPerCompanyAndAddressButHistoryAndOtherCompaniesAreFine`, `parallelInsertsOfTheSamePendingInvitationLeaveExactlyOne` (8 parallel inserts, one winner); rotation of the token comes in Phase 2 |
| AC-INV-16 | Pending | Phase 3 | |
| AC-INV-17 | Pending | Phase 2, 3 | |
| AC-INV-18 | Pending | Phase 4 | |
| AC-INV-19 | Pending (schema ready) | Phase 1, 2 | `InvitationSchemaTests.aTokenHashIdentifiesAtMostOneInvitationAcrossAllCompanies`, `theRepositoryMapsTheTableAndFindsAnInvitationByItsTokenHashOnlyWhilePending`; the 256-bit token comes from the existing `TokenCodec` in Phase 2 |
| AC-INV-20 | Pending | Phase 5 | |
| AC-INV-21 | Pending | Phase 2, 3 | |
| AC-INV-22 | Pending | Phase 4 | |
| AC-INV-23 | Pending | Phase 2, 3 | |
| AC-INV-24 | Pending | Phase 4 | |
| AC-INV-25 | Pending | Phase 4 | |
| AC-INV-26 | Pending | Phase 3 | |
| AC-INV-27 | Pending | Phase 3 | |
| AC-INV-28 | Pending (schema ready) | Phase 1, 3 | `InvitationSchemaTests` (the partial unique index lets a new pending invitation follow an expired or revoked one); the 409 and the lazy expiry come in Phase 3 |
| AC-INV-29 | Pending | Phase 4 | |

Also closed by this slice, for the consumer it supplies: AC-AUTH-14 (inviting an Admin), AC-TEN-08 for invitations. The screens beyond the accept page belong to frontend slice F1 (D-163) and are not part of these criteria.


## Phase 1 evidence (not tied to one AC)
- Migration `V13__create_invitation.sql`: a new table only. Checks: role (never Owner), status, normalized lower-case address, token hash format, a token only while pending, outcome fields match the status, expiry after creation; unique token hash; partial unique index on pending invitations per company and address; foreign keys RESTRICT (the purge in slice 007 must delete invitations first; `InvitationSchemaTests` proves a company with invitations cannot be deleted by accident). `MigrationV13OnExistingDataTest` applies V1 to V12 with data, then V13: rows survive, Flyway validates, the new table is empty and usable.
- `InvitationRepository` (no `findById`/`findAll`; the token-hash lookup is the one unscoped read and returns only a company ID, only for PENDING invitations); `TenantRepositoryScopeTest` still passes.
- `InvitationLocks` (company row first, then the invitation, same 500 ms test timeout and the same `SET LOCAL`, refuses to run outside a transaction, another company's invitation looks missing): `InvitationLocksTests` (4).
- `RateLimiter.current` (read-only count; allowed means one more attempt is within the limit): `JdbcRateLimiterTests` (3 new). Mutation checks: making the invitation unique index non-partial fails the history test; making `current` count fails the read-only test.

## Checks run (Phase 1)
`./mvnw verify` ran 440 tests with 0 failures and 2 skipped. Contract and frontend are unchanged. Not run: Playwright E2E, CI jobs.
