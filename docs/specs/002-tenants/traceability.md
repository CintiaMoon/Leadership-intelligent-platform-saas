# SPEC-TEN Traceability (implementation status)

- Updated: 2026-10-21, after Phase 2 (authorization core). AC-TEN-10 is verified at policy level; AC-TEN-01 and 02 have only their database half; the rest is Pending.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-105 to D-120.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-TEN-01 | Partial | Phase 1 (trigger), Phase 3 | `TenantSchemaTests.aCompanyAndItsOwnerCommitTogether`, `aCompanyWithoutAnOwnerCannotCommit` (database half; service half in Phase 3) |
| AC-TEN-02 | Partial | Phase 1 (trigger), Phase 3 | `TenantSchemaTests.aFailureAfterTheCompanyInsertLeavesNothing` (database half; service half in Phase 3) |
| AC-TEN-03 | Pending | Phase 4 | |
| AC-TEN-04 | Pending | Phase 4 | |
| AC-TEN-05 | Pending | Phase 3 | |
| AC-TEN-06 | Pending | Not in 002: owned by AC-MEM-03 (slice 003); 002 supplies the unique Owner index | |
| AC-TEN-07 | Pending | Phase 3 | |
| AC-TEN-08 | Pending | Mechanism in Phase 4 (Q-14); closed in slice 003 | |
| AC-TEN-09 | Pending | Phase 4 | |
| AC-TEN-10 | Partial (policy level) | Policy level in Phase 2; endpoint level in Phase 4 (test-only routes); real endpoints in slices 003 to 007 | `CompanyPolicyTest`, `SpecMatrixParityTest` (every Permission x Role cell equals the spec table; mutation check: changing one cell fails it) |
| AC-TEN-11 | Pending | Phase 3 | |
| AC-TEN-12 | Pending | Not in 002: needs plans (slice 006); matrix cells tested in Phase 2 | |
| AC-TEN-13 | Pending | Phase 3 | |
| AC-TEN-14 | Pending | Not in 002: needs export and plans (slice 006, D-73); matrix cells tested in Phase 2 | |
| AC-TEN-15 | Pending | Phase 3 | |

## Checks not run
Phase 2: `./mvnw verify` ran (296 tests, 0 failures, 2 skipped); `npm run lint` in `contracts` passed (2 warnings, not examined); `gen:api` regenerated `schema.d.ts` for the new `NOT_FOUND` code; `npm run typecheck` in `apps/web` printed no errors. Not run: frontend unit tests, lint and build, Playwright E2E, CI jobs.

## Phase 2 evidence (not tied to one AC)
- 404 vs 403 (D-108), role loaded per request, suspension isolated per company, pending-deletion reads: `CompanyAuthorizationTests` (9; contributes to AC-TEN-03, 04, 09, which stay Pending until Phase 4).
- Repository scope (D-110): `TenantRepositoryScopeTest`.
- Denial audit and no audit for 404 probes (D-111): `CompanyAuthorizationTests`.
