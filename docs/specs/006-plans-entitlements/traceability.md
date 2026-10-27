# SPEC-PLAN Traceability (implementation status)

- Updated: 2026-10-27, after Phase 0 (approvals and spec amendments). Nothing is implemented.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL); for the frontend, in the web unit tests and Playwright.
- Plan: `plan.md` (approved 2026-10-27, Q-01 to Q-23, D-195 to D-217). Spec approved the same day with AC-PLAN-21 to AC-PLAN-25 and the gating, existing-companies, endpoint and operator sections added.
- Phases: 0 done; 1 (schema), 2 (domain and service), 3 (gating, contract and API), 4 (operator assignment), 5 (frontend), 6 (review and closure) not started.

| AC | Status | Planned | Evidence so far |
|---|---|---|---|
| AC-PLAN-01 | Pending | Phases 1, 2 | |
| AC-PLAN-02 | Pending | Phases 1, 2 | |
| AC-PLAN-03 | Pending | Phase 3 | |
| AC-PLAN-04 | Pending | Phases 2, 3 | |
| AC-PLAN-05 | Pending (member limit only; teams and assessments stay open until the domain spec, D-68, D-206) | Phase 2, 3 | |
| AC-PLAN-06 | Not applicable: no billing state exists; the only writers are the application and the operator script | - | To be stated in the closure |
| AC-PLAN-07 | Pending | Phase 3 | |
| AC-PLAN-08 | Pending | Phase 3 | |
| AC-PLAN-09 | Pending | Phases 1, 2 | |
| AC-PLAN-10 | Pending | Phase 2 | |
| AC-PLAN-11 | Pending | Phases 2, 4 | |
| AC-PLAN-12 | Pending (export has no operation yet: tested through the existing `EXPORT_DATA` test route) | Phase 3 | |
| AC-PLAN-13 | Pending | Phase 3 | |
| AC-PLAN-14 | Pending (member limit only) | Phase 3 | |
| AC-PLAN-15 | Pending | Phases 1, 2 | |
| AC-PLAN-16 | Pending | Phases 1, 2 | |
| AC-PLAN-17 | Pending | Phase 2 | |
| AC-PLAN-18 | Pending | Phase 2 | |
| AC-PLAN-19 | Pending | Phase 3 | |
| AC-PLAN-20 | Pending | Phase 3 | |
| AC-PLAN-21 | Pending | Phase 4 | |
| AC-PLAN-22 | Pending | Phase 3 | |
| AC-PLAN-23 | Pending | Phase 1 | |
| AC-PLAN-24 | Pending | Phase 3 | |
| AC-PLAN-25 | Pending | Phase 2 | |
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
