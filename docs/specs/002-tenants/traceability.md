# SPEC-TEN Traceability (implementation status)

- Updated: 2026-10-21, after Phase 0 (plan approved). Nothing is implemented; every criterion is Pending.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL).
- Plan: `plan.md`. Decisions: D-105 to D-120.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-TEN-01 | Pending | Phase 1 (trigger), Phase 3 | |
| AC-TEN-02 | Pending | Phase 1 (trigger), Phase 3 | |
| AC-TEN-03 | Pending | Phase 4 | |
| AC-TEN-04 | Pending | Phase 4 | |
| AC-TEN-05 | Pending | Phase 3 | |
| AC-TEN-06 | Pending | Not in 002: owned by AC-MEM-03 (slice 003); 002 supplies the unique Owner index | |
| AC-TEN-07 | Pending | Phase 3 | |
| AC-TEN-08 | Pending | Mechanism in Phase 4 (Q-14); closed in slice 003 | |
| AC-TEN-09 | Pending | Phase 4 | |
| AC-TEN-10 | Pending | Policy level in Phase 2; endpoint level in Phase 4 (test-only routes); real endpoints in slices 003 to 007 | |
| AC-TEN-11 | Pending | Phase 3 | |
| AC-TEN-12 | Pending | Not in 002: needs plans (slice 006); matrix cells tested in Phase 2 | |
| AC-TEN-13 | Pending | Phase 3 | |
| AC-TEN-14 | Pending | Not in 002: needs export and plans (slice 006, D-73); matrix cells tested in Phase 2 | |
| AC-TEN-15 | Pending | Phase 3 | |

## Checks not run
All, until Phase 1 lands.
