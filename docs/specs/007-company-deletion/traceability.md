# Traceability: SPEC-DEL (slice 007)

- Updated: 2026-10-30, Phase 3 (gating, contract and API). Status: **plan approved; V17 and the service merged (#88, #89); the endpoints, the later-state rules and the matrix tests implemented, awaiting merge; no worker or screen yet.**
- Plan: `docs/specs/007-company-deletion/plan.md` (Approved 2026-10-29; Q-01 to Q-38 recorded as D-218 to D-255).
- Phases: 0 to 2 done and merged (#87, #88, #89); 3 (gating, contract and API) implemented on its branch; 4 (purge) to 6 (review and closure) not started. Each needs your go-ahead.
- Approvals recorded: the security owner's approval of D-39 and D-42 (2026-10-29). Still required before the purge is enabled in production (not before it is built): legal's confirmation of D-40 and D-61, and the Phase 6 log-content check for D-41.

## Acceptance criteria

| Criterion | Status | Phase | Tests |
|---|---|---|---|
| AC-DEL-01 | Verified | 3 | |
| AC-DEL-02 | Not started | 4 | |
| AC-DEL-03 | Not started | 4 | |
| AC-DEL-04 | Not started | 4 | |
| AC-DEL-05 | Not started | 4 | |
| AC-DEL-06 | Not started | 4 | |
| AC-DEL-07 | Not started | 4 | |
| AC-DEL-08 | Not started | 4 | |
| AC-DEL-09 | Verified (service) | 2 | |
| AC-DEL-10 | Verified | 2, 3 | |
| AC-DEL-11 | Not started | 4 | |
| AC-DEL-12 | Verified | 3 | |
| AC-DEL-13 | Verified | 3 | |
| AC-DEL-14 | Verified | 3 | |
| AC-DEL-15 | Verified | 2 | |
| AC-DEL-16 | Not started | 4 | |
| AC-DEL-17 | Verified (service) | 2 | |
| AC-DEL-18 | Verified | 3 | |
| AC-DEL-19 | Verified (queued and sent) | 2 | |
| AC-DEL-20 | Verified | 3 | |
| AC-DEL-21 | Not started | 4 | |
| AC-DEL-22 | Not started | 4 | |
| AC-DEL-23 | Not started | 4 | |
| AC-DEL-24 | Not started | 4 | |
| AC-DEL-25 | Not started | 2, 4 | |
| AC-DEL-26 | Not started | 4 | |
| AC-DEL-27 | Not started | 4 | |
| AC-DEL-28 | Verified | 2, 3 | |
| AC-DEL-29 | Verified | 3 | |
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

## Phase 2: request and cancel service
- **Added:** `tenant/deletion/application`: `DeletionService` (before the lock: Owner permission, 10-minute authentication for a request only, rate limits), `DeletionTransactions` (under the company lock: Owner re-check, state, flag, deadline; cancels a pending ownership transfer through the slice 005 hook; records the request with `purge_at` = request + `app.deletion.grace-period`; audit; emails; the after-commit `CompanyDeletionRequested` event with no listener yet, Q-38), `DeletionEmails` and three outbox handlers, `DeletionProperties`, `DeletionRateLimits` (5 per user and 10 per company per hour, request and cancel together), `DeletionConflictException` (409 `CONFLICT`), `DeletionView`. `Company` maps the three request columns; `CompanyStatus` has the five values; `CompanyPolicy` treats every status but ACTIVE like PENDING_DELETION (fail closed, the deviation approved with the Phase 2 plan); `AuthEmails.companyDeletion`.
- **Clarification of Q-26 (to confirm):** the email cooldown is per kind (requested, cancelled), not one wave for both. With one shared wave a cancellation within the hour would never be announced, so the Owner and the Admins would keep believing the deletion stands. A request-and-cancel loop still sends at most one email of each kind per hour, and every step is audited (`emails_held_back` marks the held-back ones).
- **Behaviour worth knowing:** a second request on a pending company is refused by the policy as 403 (every state change but the cancellation is, AC-DEL-12), not as 409; the 409 comes from the check under the lock. Members other than the Owner and the Admins get the notice at request time but no email about the cancellation, as the plan decided (Q-27).
- **Tests:** `DeletionServiceTests` (19: the request and its effects, who may ask, the authentication window, the flag and the state, the transfer ended and audited, eight simultaneous requests (one transition), a request and a cancellation that waited for the lock while the Owner changed hands, rollback when the outbox fails, recipients and texts, a request email dropped after a cancellation, the cancellation without recent authentication, the deadline one millisecond before, at and after, a new request after a cancellation, the cooldown and its end, the sixth operation refused) and `CompanyPolicyTest.everyStatusButActiveIsClosedLikePendingDeletion`.
- **Mutation checks, each restored:** 14, all detected (state check, flag check, Owner re-check on request and on cancel, transfer not cancelled, deadline off by one, cooldown, notice mapping, event not published, authentication window, limits, the two email checks, the policy). One was not detected at first: the Owner re-check in the cancellation; a test that waits for the lock while the Owner changes hands now covers it.
- **Existing tests changed:** `InvitationRulesTest` and `MembershipChangesTest` stated "closed while pending deletion" as `== PENDING_DELETION` and counted their combinations for two statuses; they now state "closed unless ACTIVE" and count over `CompanyStatus.values()`, so they cover the three purge states too. Their subjects (`InvitationRules`, `MembershipChanges`) needed no change: both already go through `CompanyPolicy`.
- **Limits:** the reminders (7 and 1 day) and the purge are Phase 4; the endpoints, the contract and the later-state rules are Phase 3; no screen is built. The end-to-end behaviour over HTTP, the denial audit and the per-row matrix tests are Phase 3.

## Phase 3: gating, contract and API
- **Added:** `POST` and `DELETE /api/v1/companies/{companyId}/deletion` (`DeletionController`, thin; both answer 200 with `{status, requestedAt, purgeAt}`; the security chain opens the two routes, authentication and CSRF as for the other writes); `deletionRequestedAt` and `purgeAt` on `GET /api/v1/companies/{companyId}` (null while ACTIVE); the status enum of the contract has the three purge states; OpenAPI, `schema.d.ts`, the Postman collection (two requests) and `docs/architecture/audit-events.md` updated. `CompanyPolicy`: while PENDING_DELETION the Owner reads everything and can cancel and nobody else can do anything; in PURGE_QUEUED, PURGING and PURGE_FAILED nobody can do anything but the Owner reading the company details (AC-DEL-29), so a cancellation there is 403, and a cancellation in PENDING_DELETION after the deadline is 409.
- **Tests:** `DeletionHttpTests` (11: the response fields and the absence of any address or user id, every role against the endpoint and the audited denial, outsider 404, no CSRF 403, the 10-minute rule for the request and none for the cancellation, flag and state conflicts, the sixth operation 429 with `Retry-After`, the cancel deadline, tenant isolation, a malformed id, the company details and the company list in each state, the three purge states over HTTP); `PendingDeletionMatrixTests` (97: one dynamic test per matrix row while pending, and per row for each purge state, with expectations written from the spec and a check that the two lists of rows agree with the `stateChanging` flag); `PendingDeletionUnderLockTests` (7, one per write path: role change, suspend and remove, reactivate, leave, invitation create, resend and revoke, acceptance, ownership start, cancel and accept: a write that waited for the company lock is refused when a deletion request committed meanwhile); `CompanyPolicyTest` (the purge states, and no status more open than PENDING_DELETION).
- **Mutation checks, each restored:** 15 in this phase, all detected: the under-lock status check of every write path (8), the purge-state rules (2), the Admin read in PENDING_DELETION, the cancel authentication rule, the unopened route, the conflict mapping, the details response. The acceptance check was NOT detected at first: its test refused for another reason (the invitee's email was never verified because a leftover mail was picked for the verification token). I added a control (the same steps without a deletion are accepted) and `mail.clear()`; it is now detected. The earlier green result for that test was a false positive.
- **Existing tests that already covered a row in PENDING_DELETION** (mapped here, not duplicated): `InvitationApiTests`/`InvitationServiceTests.noInvitationOperationIsAllowedWhileTheCompanyIsPendingDeletion`, `MemberApiTests.whileACompanyIsPendingDeletionNoMemberChangeIsAllowedToAnyone`, `OwnershipTransferApiTests.noOwnershipOperationIsAllowedWhileTheCompanyIsPendingDeletion`, `OwnershipTransferServiceTests` (start and complete), `CompanyAuthorizationTests.whilePendingDeletionOnlyTheOwnerCanRead`, `CompanyApiTests.aCompanyPendingDeletionIsListedOnlyForItsOwner`, `InvitationDeliveryTests` (no email for a closing company).
- **Behaviour worth knowing:** an anonymous caller who sends a POST without a session gets 403 `CSRF_REJECTED` (the CSRF check comes first), as for every other write; the company list shows no status, so the screen of Phase 5 will need the details call or a status on the list; the BFF allowlist (`apps/web/src/lib/server/bff.ts`) does not forward the two routes yet: Phase 5 adds them with the screen.
- **Web:** `npm run gen:api`, typecheck, lint and the unit tests pass with the wider status enum; no screen changed.
- **Limits:** no worker or reminders (Phase 4), no screen (Phase 5). The Playwright suite and the CI `e2e` job were not run.
