# Implementation Plan: SPEC-DEL (slice 007)

- Status: **Proposed 2026-10-29, awaiting your approval.** Nothing is built. Questions Q-01 to Q-20 are proposals; each becomes a decision (D-218 and following in the register) when you approve it.
- Spec: `docs/specs/007-company-deletion/spec.md` (SPEC-DEL, Draft; the decisions D-31, D-37 to D-39, D-42, D-53, D-57 to D-60 inside it were decided 2026-10-16/17).
- Related: SPEC-TEN (matrix, pending-deletion rule, lifecycle), SPEC-MEM (D-54 flag), SPEC-OWN (transfer cancelled by a request, D-31, D-32), SPEC-PLAN (deletion allowed in every plan state, `user_trial_grant` kept, D-216), SPEC-AUTH (10-minute recent authentication, D-15), ADR-001, the register (D-05 outbox, D-100 retention).
- Date: 2026-10-29. Written from the spec, the register and the code as merged through PR #86; no code was changed.

## 1. Readiness check (CLAUDE.md workflow step 1)
| Item | State | Action |
|---|---|---|
| SPEC-DEL status | Draft; its decisions are made, the spec itself is not Approved | Q-18 lists the amendments |
| D-15, D-31, D-37, D-38, D-39, D-42, D-53, D-57 to D-60 | Decided 2026-10-16/17 | Built as decided |
| D-40 (audit retention, legal review pending), D-41 (external systems, verification pending), D-61 (retained financial records, legal confirmation pending) | **Proposed, not decided.** The register says they block 007 | **Q-02:** you confirm them as the working basis, or tell me to wait. Phases 1 to 3 do not depend on them; Phase 4 (purge) and the closure do |
| 🔒 decisions (D-39, D-40, D-42) | Security owner approval needed before implementation | You confirm in Phase 0 that the security owner has approved D-39 and D-42 as decided and D-40 as proposed |
| What already exists | `company.status` allows `ACTIVE` and `PENDING_DELETION` (V9) but nothing sets the second. `Permission.REQUEST_COMPANY_DELETION` and `CANCEL_COMPANY_DELETION` exist, Owner only, with `StateAccess.ALWAYS` (allowed in every plan state, slice 006). `CompanyPolicy.permits` already denies everything but Owner reads and the cancellation while `PENDING_DELETION`. `company.ownership_resolution_at` (the D-54 flag) exists. The outbox, the recent-authentication check (`StepUpService`, 10 minutes), `CompanyLocks`, `AuditLog` and the retention job exist | Reused, not rebuilt |
| What does not exist | The request and cancel operations, the grace period, the purge states, the purge worker, the manifest, the tombstone, the ledger replay, the emails, the screens, and the purge runbook (a stub) | This slice |
| Design for the screens | None in `docs/design` | Section 7 is a short brief; building follows D-171 (from the brief, no Figma) |

## 2. Assumptions and trust boundaries
- The browser is untrusted. Whether a deletion may be requested, cancelled or purged is decided on the server from the stored state and the injected clock. A company ID in a path is a selector only (D-108): a non-member gets 404.
- Only an ACTIVE Owner can request or cancel. Requesting needs a login or step-up within 10 minutes (AC-DEL-13); cancelling does not (D-37, D-53), and both notify the Owner and the Admins.
- Every state change locks the company row first, then any transfer row (D-32, D-125). The purge worker uses the same order. The state and the deadline are checked again under the lock, as the plan gate is.
- The purge is the most destructive code in the product. It is disabled by default, has a dry-run mode, rechecks state and deadline under the lock, deletes only rows that belong to the company through registered handlers, and never runs in an environment until a reviewed runbook is followed (D-42, AC-DEL-08).
- The tombstone holds three fields and nothing else (D-39). Audit events are kept and carry no purged company content (AC-DEL-11).
- No network call and no email inside a database transaction (CLAUDE.md 10): emails go through the outbox.
- Time is UTC (`Instant`, `timestamptz`). `purge_at` is the request instant plus the configured grace period (30 days).

## 3. Decisions and answers to the open points of the spec (proposed)
| # | Topic | Proposal |
|---|---|---|
| Q-01 | Scope and phases | Phase 0 approvals; Phase 1 schema; Phase 2 domain and service (request, cancel, emails, audit); Phase 3 gating, contract and API; Phase 4 purge engine, manifest, tombstone, ledger; Phase 5 frontend; Phase 6 review, runbooks and closure. Each phase is its own PR and needs your go-ahead |
| Q-02 | D-40, D-41, D-61 are Proposed | **Decision for you.** Proposal: treat them as the working basis (12-month audit retention, no per-company external cleanup, no retained financial records), build Phases 1 to 3 now, and record in the closure that legal confirmation is pending. Phase 4 starts only after you say the working basis stands. The 12-month audit deletion itself (D-40) is not built here: it needs a retention design and legal sign-off, and no acceptance criterion requires it (Q-14) |
| Q-03 | Lifecycle on the company row | Extend `company.status` to `ACTIVE`, `PENDING_DELETION`, `PURGE_QUEUED`, `PURGING`, `PURGE_FAILED` (the final state PURGED/TOMBSTONED is the company row gone and a tombstone present). Add nullable `deletion_requested_at`, `deletion_requested_by`, `purge_at`, `purge_attempts`, `purge_next_attempt_at`, `purge_claimed_until`, `purge_error_code` (an enum name, never a message). A CHECK ties the columns to the status. A trigger allows only the transitions of the spec: ACTIVE to PENDING_DELETION, PENDING_DELETION to ACTIVE (cancel) or PURGE_QUEUED, PURGE_QUEUED to PURGING, PURGING to PURGE_FAILED, PURGE_FAILED to PURGE_QUEUED. `CompanyStatus` and `CompanyPolicy` change from "is PENDING_DELETION" to "is not ACTIVE" so a new state fails closed |
| Q-04 | Grace period | `app.deletion.grace-period` (default 30 days, D-37). `purge_at = deletion_requested_at + grace`, stored, so a later change of the setting does not move an existing deadline. Tests use a short value through the test profile |
| Q-05 | Request | `POST /api/v1/companies/{companyId}/deletion`, no body (**no reason is collected**: the spec says "if collected" and a free-text reason would be personal data to purge). Owner only, recent authentication within 10 minutes (403 `REAUTHENTICATION_REQUIRED` otherwise), rejected with 409 when the company is flagged for ownership resolution (AC-DEL-20) or is not ACTIVE. In one transaction: lock the company, lock and cancel a pending ownership transfer with its audit event (AC-DEL-15, D-31), set PENDING_DELETION with the instants, audit `company.deletion.requested`, and enqueue the emails. Concurrent requests: exactly one transition (AC-DEL-09) because the row is locked and the state rechecked. A repeat request on a pending company is 409, not a second transition |
| Q-06 | Cancel | `DELETE /api/v1/companies/{companyId}/deletion`. Owner only, no recent authentication (D-37). Under the lock: the state is PENDING_DELETION **and** `now < purge_at`, atomically (AC-DEL-10); otherwise 409. Sets ACTIVE and clears the deletion columns, audits `company.deletion.cancelled`, enqueues the emails. A cancelled company can request again |
| Q-07 | What the later states look like | PURGE_QUEUED, PURGING and PURGE_FAILED deny every operation for every role except that an Owner can read the company's lifecycle (so the screen can say it is being deleted). The cancel is rejected from `purge_at` on even before the worker moves the row to PURGE_QUEUED (the deadline check, AC-DEL-10). Reads in PENDING_DELETION stay Owner-only (AC-DEL-14) |
| Q-08 | Gating | One place: `CompanyPolicy.permits` and the authorization service, as for plans. The rule becomes "not ACTIVE ⇒ only what Q-07 and AC-DEL-12 allow". The order of checks is lifecycle, then role, then plan state; deletion request and cancel stay allowed in every plan state. Every state-changing row of the SPEC-TEN matrix gets one test in PENDING_DELETION (AC-DEL-18), over HTTP through the existing routes and test routes; invitation acceptance, leaving and ownership transfers are covered by their own tests (D-38). The under-lock re-check of slice 006 also checks the lifecycle, so a request that began before a deletion request cannot finish after it |
| Q-09 | Notifications | Two outbox event types, `COMPANY_DELETION_REQUESTED` and `COMPANY_DELETION_CANCELLED`, one outbox row per recipient (the Owner and every ACTIVE Admin at that moment), idempotency key per event and recipient, written in the same transaction (AC-DEL-19). The text names the company and, for a request, the purge date and how to cancel. No link carries a token. A failing mail provider never blocks the request |
| Q-10 | Outbox rows and the company | `outbox_event` has no company column, so AC-DEL-22 ("the company's outbox records are deleted") cannot be done safely from the payload. Proposal: add a nullable `outbox_event.company_id` (indexed, no foreign key) in the Phase 1 migration, set it for company-scoped events (invitation, transfer, deletion emails) and backfill existing rows from the payload where it carries the company. Auth emails keep it null and are not purged. The column also puts the table under the manifest check (Q-12) |
| Q-11 | Purge worker | A scheduled job in the API process, claiming work with `FOR UPDATE SKIP LOCKED` on due companies (the outbox pattern of D-05). Properties: `app.purge.enabled` (default `false`, AC-DEL-08), `app.purge.dry-run`, `app.purge.batch-size`, `app.purge.interval`. Per company, one transaction: lock the company; recheck status and `purge_at <= now` (AC-DEL-02, AC-DEL-03: a restored company is ACTIVE and is skipped); move PENDING_DELETION to PURGE_QUEUED to PURGING; run the handlers; write the tombstone and the `company.purged` audit event (company ID, counts per handler, nothing else, AC-DEL-11); delete the company row. A crash before commit leaves the state as it was, so a duplicate or retried run is safe (AC-DEL-04). A claim has a lease (`purge_claimed_until`) so a dead worker's company is picked up again |
| Q-12 | Manifest and the CI check | `PurgeHandler` interface (`module`, `tables`, `purge(companyId)`, `count(companyId)`), one per module: membership, invitation, ownership transfer, plan (subscription and usage; **not** `user_trial_grant`), outbox. A registry bean collects them. A JUnit test on Testcontainers PostgreSQL reads `information_schema` and fails when a table with a `company_id` column has no handler, with an explicit exception list that needs a reason each: `audit_event` (kept, D-58), `user_trial_grant` (kept, D-216), the tombstone. It runs in the `api` CI job (AC-DEL-21). After a purge a verification query counts rows by company ID in every registered table (AC-DEL-07) |
| Q-13 | Failure and retry | A failed attempt rolls back, then in a short separate transaction sets PURGE_FAILED with `purge_attempts + 1`, `purge_error_code` and `purge_next_attempt_at` (exponential: 1, 5, 25, 125 minutes, then stop; D-60). A retry moves PURGE_FAILED to PURGE_QUEUED. After the fifth failure it stays PURGE_FAILED, logs a stable `purge.failed.final` error line and sets a gauge metric (the alert: the actual paging belongs to the deployment slice, D-08, recorded as an open item). An operator resumes through the runbook with a reviewed script that resets the counters, as the other operator scripts do |
| Q-14 | Audit retention (D-40) | Audit events are kept by the purge. Deleting them after 12 months is not built here (Proposed, legal review pending, no criterion). The closure records it as an open item |
| Q-15 | Deletion ledger replay (D-59, AC-DEL-23) | A `LedgerReplay` service plus an operator command (a runbook step, not scheduled): for every tombstone, run every handler's purge for that company ID (idempotent, they delete nothing that is not there) and report the counts. Tested against a database restored from a snapshot that still holds a purged company's rows. Added to `restore-from-backup.md` |
| Q-16 | Subscription and users | The purge deletes `company_subscription` (V16's triggers have no DELETE rule; a test proves it), invitations, ownership transfers, memberships and company-scoped outbox rows, then the company. It keeps user accounts (a user left with no company simply has none, D-58) and `user_trial_grant`. Sessions are untouched |
| Q-17 | Frontend (Phase 5) | Section 7 |
| Q-18 | Spec amendments | SPEC-DEL becomes Approved with: the state list of Q-03 (PURGED/TOMBSTONED means the row is gone and a tombstone exists), the cancel deadline wording of Q-06, the later-state rule of Q-07, the alert wording of Q-13, the outbox column of Q-10, no collected reason (Q-05), and the replay as an operator command (Q-15). The register rows D-40, D-41 and D-61 stay Proposed until legal and verification confirm |
| Q-19 | Operator controls | `purge-dry-run-and-enable.md` is finished: reading the dry-run report, the conditions to enable (a second approver, a recent restore test, the dry-run reviewed), resuming PURGE_FAILED, and a test record. The first production enablement is the DevOps lead's, not this slice's (D-42) |
| Q-20 | Hand-offs from slice 006 | `user_trial_grant` and the `tenant.company.created` audit rows survive the purge (tests). Every write added here re-checks the lifecycle and the plan state under the company lock. DB roles (a runtime role that cannot run the purge's `DELETE`s outside the job) are an open item for the deployment slice |

## 4. Phases
### Phase 0: approvals
This plan approved or amended; Q-02 answered; D-218 and following recorded; SPEC-DEL amended (Q-18); no code.

### Phase 1: schema
`V17` (Q-03, Q-10): the status values and lifecycle columns with their CHECKs and the transition trigger, the `company_tombstone` table (insert-only, three columns, no foreign key, no update or delete), `outbox_event.company_id` with its backfill. Schema tests with mutation checks on every constraint and trigger; the migration on a database with V1 to V16 data.

### Phase 2: domain and service
`CompanyLifecycle` (pure transitions), `DeletionService` with request and cancel (Q-05, Q-06), the transfer cancellation, the emails and audit events (Q-09), the configured grace period. PostgreSQL tests: concurrent requests (exactly one transition), request against cancel, request against an ownership-transfer accept, the exact deadline (one millisecond before, at, after), rollback when the outbox write fails.

### Phase 3: gating, contract and API
The policy change to "not ACTIVE" (Q-07, Q-08), the authorization service and the under-lock re-checks, the two endpoints and the lifecycle fields on the company view, OpenAPI, `schema.d.ts`, Postman. HTTP tests: every role in every lifecycle state, one test per state-changing matrix row in PENDING_DELETION, the step-up requirement, 404 for non-members, tenant isolation, the denial audit. This phase does not depend on D-40, D-41 or D-61.

### Phase 4: purge engine (starts after Q-02)
`PurgeHandler`s and the registry, the worker with claim and lease, dry-run, the disabled default, the retry and backoff, the tombstone and the completion audit event, the manifest test, the verification query, the ledger replay (Q-11 to Q-16). PostgreSQL tests: early execution, a stale job against a restored company, duplicate workers (many threads), crash and retry (a failing handler at each position), five failures end in PURGE_FAILED, dry-run deletes nothing, disabled default, tombstone content, `user_trial_grant` and audit events kept, the replay, with mutation checks on the guards.

### Phase 5: frontend
The Owner's "Delete company" action with recent-authentication handling, the pending page with the date and Cancel, what other members and later states see, tests, Playwright (request, pending, cancel, a non-Owner), axe, keyboard pass, screenshots at three widths.

### Phase 6: review and closure
Read-only security and code reviews (the purge gets the closest look), fixes, traceability, `purge-dry-run-and-enable.md` and `restore-from-backup.md` finished, the privacy note about the 40-day backups, the unrun checks and open items listed, closure for your sign-off.

## 5. Expected files (approximate)
- Backend: `V17__company_lifecycle_and_tombstone.sql`; `tenant/deletion/**` (domain: lifecycle; application: `DeletionService`, emails, `PurgeWorker`, `PurgeRegistry`, `LedgerReplay`; web: controller and DTOs); a `PurgeHandler` in each of `tenant`, `invitation`, `tenant/ownership`, `plan` and the outbox package; `CompanyStatus`, `CompanyPolicy`, `CompanyAuthorizationService`, `CompanyView`, the outbox writers (company ID), the two email handlers; configuration properties.
- Contract and clients: `contracts/openapi.yaml`, `apps/web/src/lib/api/schema.d.ts`, `lib/api/messages.ts`, Postman collection.
- Frontend: `components/deletion/**`, a settings page for the company, banners, tests and e2e specs.
- Operations and docs: `docs/runbooks/operations/purge-dry-run-and-enable.md`, `restore-from-backup.md`, `docs/specs/007-company-deletion/traceability.md`, the register, the SPEC-DEL amendments, `docs/architecture/audit-events.md`.

## 6. Acceptance-criteria coverage
| Criteria | Where |
|---|---|
| AC-DEL-01, 13, 20 | Phase 3 (Owner only, step-up, flag check) |
| AC-DEL-09, 15, 17, 19 | Phase 2 (one transition under concurrency, transfer cancelled, cancel audited, emails queued) |
| AC-DEL-10 | Phases 2 and 3 (state and deadline checked atomically) |
| AC-DEL-02, 03, 04, 05, 24 | Phase 4 (deadline, stale job, duplicate execution, failure and retry) |
| AC-DEL-06, 11, 22 | Phase 4 (tombstone content, completion audit event, what is deleted and what stays) |
| AC-DEL-07, 21 | Phase 4 (verification query, manifest CI check) |
| AC-DEL-08, 16 | Phase 4 (disabled default, dry-run) |
| AC-DEL-12, 14, 18 | Phase 3 (every write rejected in PENDING_DELETION, Owner-only reads, one test per matrix row) |
| AC-DEL-23 | Phase 4 (ledger replay) |

## 7. Frontend brief (Phase 5)
- **Delete company** (Owner only, in a company settings page): a plain explanation of what is deleted and what is kept, the 30-day period and that it can be cancelled until then; a confirmation that needs the company name typed; if the last authentication is older than 10 minutes the existing step-up form is shown first. No bulk or hidden path.
- **Pending deletion:** a persistent banner for the Owner ("Scheduled for deletion on 28 Nov 2026 14:00 UTC") with Cancel deletion; the rest of the app is read-only for the Owner and the controls are shown as not available with the reason, as in slice 006.
- **Other members:** one calm page ("This company is scheduled for deletion. Ask the Owner."); no data is shown.
- **Later states:** "This company is being deleted" for the Owner; nothing for others.
- Not built: export before deletion (a separate slice), a reason field, an undo after the purge.

## 8. Risks
- **The purge deletes the wrong rows.** Mitigated by handlers that delete only by the company ID, a manifest test that fails when a table is not registered, a verification query, dry-run, a disabled default, and mutation checks on every guard.
- **A new status passes as ACTIVE.** `CompanyPolicy` currently tests `== PENDING_DELETION`. The change to "not ACTIVE" and a test over every status value make it fail closed.
- **Race between a deletion request and anything else that writes.** The company lock first, the lifecycle rechecked under it in every write path (the slice 006 pattern), tested with the request on one thread and an operation waiting on the lock on another.
- **The Owner trigger and the purge.** `trg_membership_keeps_owner` is deferred and skips a company that no longer exists, so deleting every membership and then the company in one transaction passes; a test proves it, and also that a purge that fails halfway leaves the company intact.
- **Outbox rows without a company.** Q-10 adds the column and backfills it; rows it cannot attribute stay and are listed in the traceability.
- **Backups and restores.** Purged data stays in backups up to 40 days (D-40); the ledger replay (Q-15) is the control and is part of the restore runbook. The privacy note must say so.
- **Proposed decisions (D-40, D-41, D-61).** Phase 4 does not start before Q-02 is answered. If legal changes them, the purge handlers and the tombstone are the places that change.
- **An alert that nobody receives.** Q-13 produces a log line and a metric; wiring it to paging belongs to the deployment slice and is recorded as open.

## 9. Checks to run
`./mvnw verify` (Testcontainers PostgreSQL), contract lint and the API-type drift check, web unit tests, typecheck, lint, build, Playwright, the CI jobs, `V17` validated on an empty database and on one with V1 to V16 data, the purge run with real data in a throwaway database (dry-run first), the ledger replay against a restored snapshot. Checks not run are listed in the traceability file.

## 10. Approval (pending)
1. Q-01 to Q-20 approved as written or amended. **Q-02 in particular:** are D-40, D-41 and D-61 the working basis, and has the security owner approved D-39 and D-42 (🔒)?
2. SPEC-DEL approved with the amendments of Q-18.
3. The phase order approved, each phase its own PR; each phase still needs your go-ahead to start.
