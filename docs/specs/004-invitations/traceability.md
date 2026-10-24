# SPEC-INV Traceability (implementation status)

- Updated: 2026-10-24, after Phase 0 (spec and plan decisions approved). Nothing is implemented; every criterion is Pending.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL), and for AC-INV-20 and the accept flow, in the web unit tests and Playwright.
- Plan: `plan.md`. Decisions: D-144 to D-163. Spec approved 2026-10-24 with AC-INV-27 to AC-INV-29 added.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-INV-01 | Pending | Phase 2, 3 | |
| AC-INV-02 | Pending | Phase 4 | |
| AC-INV-03 | Pending | Phase 3 (revoked), Phase 4 | |
| AC-INV-04 | Pending | Phase 4 (PostgreSQL concurrency) | |
| AC-INV-05 | Pending | Phase 1, 2 | |
| AC-INV-06 | Pending | Phase 3, 4 | |
| AC-INV-07 | Pending | Phase 5 | |
| AC-INV-08 | Pending | Phase 4 | |
| AC-INV-09 | Pending | Phase 4 | |
| AC-INV-10 | Pending | Phase 4 | |
| AC-INV-11 | Pending | Phase 2, 4 | |
| AC-INV-12 | Pending | Mechanism in Phase 4; closed in slice 006 | |
| AC-INV-13 | Pending | Phase 3 | |
| AC-INV-14 | Pending | Phase 4 | |
| AC-INV-15 | Pending | Phase 1, 2, 3 | |
| AC-INV-16 | Pending | Phase 3 | |
| AC-INV-17 | Pending | Phase 2, 3 | |
| AC-INV-18 | Pending | Phase 4 | |
| AC-INV-19 | Pending | Phase 1, 2 | |
| AC-INV-20 | Pending | Phase 5 | |
| AC-INV-21 | Pending | Phase 2, 3 | |
| AC-INV-22 | Pending | Phase 4 | |
| AC-INV-23 | Pending | Phase 2, 3 | |
| AC-INV-24 | Pending | Phase 4 | |
| AC-INV-25 | Pending | Phase 4 | |
| AC-INV-26 | Pending | Phase 3 | |
| AC-INV-27 | Pending | Phase 3 | |
| AC-INV-28 | Pending | Phase 1, 3 | |
| AC-INV-29 | Pending | Phase 4 | |

Also closed by this slice, for the consumer it supplies: AC-AUTH-14 (inviting an Admin), AC-TEN-08 for invitations. The screens beyond the accept page belong to frontend slice F1 (D-163) and are not part of these criteria.

## Checks not run
All, until Phase 1 lands.
