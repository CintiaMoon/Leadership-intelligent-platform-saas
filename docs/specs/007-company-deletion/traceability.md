# Traceability: SPEC-DEL (slice 007)

- Updated: 2026-10-30, Phase 1 (schema). Status: **plan approved; V17 written and tested, awaiting merge; nothing else built.**
- Plan: `docs/specs/007-company-deletion/plan.md` (Approved 2026-10-29; Q-01 to Q-38 recorded as D-218 to D-255).
- Phases: 0 (approvals) done and merged (#87); 1 (schema) implemented on its branch; 2 (service) to 6 (review and closure) not started. Each needs your go-ahead.
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

## Phase 1: schema (migration V17)
- **Added:** `V17__company_lifecycle_and_tombstone.sql`: the lifecycle on `company` (statuses `ACTIVE`, `PENDING_DELETION`, `PURGE_QUEUED`, `PURGING`, `PURGE_FAILED`; `deletion_requested_at/by`, `purge_at`, `purge_attempts`, `purge_next_attempt_at`, `purge_claimed_until`, `purge_error_code`, the hold `purge_hold_ticket/set_at`), five CHECKs, three partial indexes for the worker, and the transition trigger `assert_company_lifecycle`; `company_tombstone` (three columns, no foreign key, insert-only including TRUNCATE, refused at commit while the company row exists); `outbox_event.company_id` with an index, a backfill from the payload and an insert trigger that copies the company from the payload (so every existing writer is covered without a code change; the plan said the writers would set it).
- **Deviations from the plan, all inside Phase 1:** (1) the insert trigger on `outbox_event` instead of changing each writer; (2) a hand-edited `PENDING_DELETION` row is backfilled (request = its last update, actor = its Owner, deadline 30 days later) instead of failing the migration, because the new CHECK would otherwise reject it; production is expected to have none, so the count of such rows must be read when this runs there; (3) the database does not compare `purge_at` with its own clock, so the deadline stays the application's decision with the injected clock (AC-DEL-02 is Phase 4).
- **Existing tests changed:** 14 places that set `status = 'PENDING_DELETION'` directly now use `TestLifecycle.markPendingDeletion`, which records the request the CHECK demands (and `CompanyApiTests.insertCompany` marks after the Owner exists).
- **Tests:** `CompanyLifecycleSchemaTests` (15: every state's fields, the checks, all 25 state pairs, the immutable request and the cancellation that clears it, the hold in every state, the indexes, the tombstone's columns, insert-only and commit-time rules, the outbox company) and `MigrationV17OnExistingDataTest` (2: V1 to V16 data with an ACTIVE company, a hand-edited pending company and five outbox events; and an empty database).
- **Mutation checks, each restored:** cancellation transition removed, retry transition removed, `purge_at` and `deletion_requested_by` allowed to change, the three tombstone triggers removed, the outbox insert trigger removed, deadline equal to the request, the hold moment without a ticket, the outbox backfill removed, the pending backfill deadline wrong: all 12 detected. A real defect was found on the way: the first hold CHECK accepted a hold moment with no ticket (`NULL AND true` is `NULL`, which a CHECK accepts); fixed in V17 before it shipped.
- **Limits:** `ck_company_status` cannot fail alone: the lifecycle CHECK rejects an unknown status too, and PostgreSQL reports whichever it evaluates first (a second line, like four guards of the plan script). The application does not write any of the new columns yet (Phase 2); `CompanyStatus` and `CompanyPolicy` are unchanged (Phase 3).
