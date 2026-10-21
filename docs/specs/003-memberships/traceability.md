# SPEC-MEM Traceability (implementation status)

- Updated: 2026-10-21, after Phase 0 (plan approved). Nothing is implemented; every criterion is Pending.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-123 to D-140.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-MEM-01 | Pending | Phase 2, 3 | |
| AC-MEM-02 | Pending | Phase 2, 3 | |
| AC-MEM-03 | Pending | Phase 4 (on the slice 002 schema guarantees) | |
| AC-MEM-04 | Pending | Phase 3, 4 | |
| AC-MEM-05 | Pending | Slice 002 (`TenantSchemaTests`); cross-referenced | |
| AC-MEM-06 | Pending | Phase 3 | |
| AC-MEM-07 | Pending | Phase 3 | |
| AC-MEM-08 | Pending | Phase 4 | |
| AC-MEM-09 | Pending | Phase 3 | |
| AC-MEM-10 | Pending | Phase 2, 3 | |
| AC-MEM-11 | Pending | Phase 2, 3 | |
| AC-MEM-12 | Pending | Phase 2, 3 | |
| AC-MEM-13 | Pending | Phase 1, 3 | |
| AC-MEM-14 | Pending | Slice 001 (enforced); cross-reference test in Phase 3 | |
| AC-MEM-15 | Pending | Phase 2, 3 | |
| AC-MEM-16 | Pending | Phase 4 | |
| AC-MEM-17 | Pending | Not in 003: hook in Phase 2, closed in slice 006 | |
| AC-MEM-18 | Pending | Phase 5 (runbook SQL and test) | |

Also closed by this slice, for the consumer it supplies: AC-AUTH-14 (promoting or demoting an Admin; it stays open for the other four operations) and AC-AUTH-23 (session rotation after a change to one's own membership), and AC-TEN-08 for memberships (D-136).

## Checks not run
All, until Phase 1 lands.
