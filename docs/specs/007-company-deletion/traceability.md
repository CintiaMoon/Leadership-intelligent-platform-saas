# Traceability: SPEC-DEL (slice 007)

- Updated: 2026-10-29, Phase 0 (approvals). Status: **plan approved, nothing built.**
- Plan: `docs/specs/007-company-deletion/plan.md` (Approved 2026-10-29; Q-01 to Q-38 recorded as D-218 to D-255).
- Phases: 0 (approvals, this file) done; 1 (schema) to 6 (review and closure) not started. Each needs your go-ahead.
- Approvals recorded: the security owner's approval of D-39 and D-42 (2026-10-29). Still required before the purge is enabled in production (not before it is built): legal's confirmation of D-40 and D-61, and the Phase 6 log-content check for D-41.

## Acceptance criteria

| Criterion | Status | Phase | Tests |
|---|---|---|---|
| AC-DEL-01 | Not started | 3 | |
| AC-DEL-02 | Not started | 4 | |
| AC-DEL-03 | Not started | 4 | |
| AC-DEL-04 | Not started | 4 | |
| AC-DEL-05 | Not started | 4 | |
| AC-DEL-06 | Not started | 4 | |
| AC-DEL-07 | Not started | 4 | |
| AC-DEL-08 | Not started | 4 | |
| AC-DEL-09 | Not started | 2 | |
| AC-DEL-10 | Not started | 2, 3 | |
| AC-DEL-11 | Not started | 4 | |
| AC-DEL-12 | Not started | 3 | |
| AC-DEL-13 | Not started | 3 | |
| AC-DEL-14 | Not started | 3 | |
| AC-DEL-15 | Not started | 2 | |
| AC-DEL-16 | Not started | 4 | |
| AC-DEL-17 | Not started | 2 | |
| AC-DEL-18 | Not started | 3 | |
| AC-DEL-19 | Not started | 2 | |
| AC-DEL-20 | Not started | 3 | |
| AC-DEL-21 | Not started | 4 | |
| AC-DEL-22 | Not started | 4 | |
| AC-DEL-23 | Not started | 4 | |
| AC-DEL-24 | Not started | 4 | |
| AC-DEL-25 | Not started | 2, 4 | |
| AC-DEL-26 | Not started | 4 | |
| AC-DEL-27 | Not started | 4 | |
| AC-DEL-28 | Not started | 2, 3 | |
| AC-DEL-29 | Not started | 3 | |
| AC-DEL-30 | Not started | 6 | |

## Open items

- Legal confirmation of D-40 (audit retention) and D-61 (retained financial records); verification of D-41 (the Phase 6 log check). Required before enabling the purge in production.
- Deleting audit events after 12 months is not built in this slice (plan Q-14).
- Paging for the purge alerts belongs to the deployment slice (D-08).
- Dependencies recorded: an export slice may export during PENDING_DELETION (Q-28); the billing slice cancels the subscription on a deletion request (Q-38).
