# SPEC-INV Traceability (implementation status)

- Updated: 2026-10-24, after Phase 3 (management endpoints). Verified over HTTP: AC-INV-01, 16, 17, 21, 23, 27, 28 and the creating, resending and revoking sides of AC-INV-13 and AC-INV-15. Everything about accepting an invitation waits for Phase 4.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL), and for AC-INV-20 and the accept flow, in the web unit tests and Playwright.
- Plan: `plan.md`. Decisions: D-144 to D-163. Spec approved 2026-10-24 with AC-INV-27 to AC-INV-29 added.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-INV-01 | Verified | Phase 2, 3 | `InvitationApiTests.everyOperationForEveryRoleMatchesTheMatrixOverHttp` (4 roles x create, list, resend, revoke, plus an outsider), `InvitationServiceTests.anAgentAndAViewerAreRefusedBeforeAnyLock...`; mutation check on the route security fails the revoke tests |
| AC-INV-02 | Pending | Phase 4 | |
| AC-INV-03 | Pending | Phase 3 (revoked), Phase 4 | |
| AC-INV-04 | Pending | Phase 4 (PostgreSQL concurrency) | |
| AC-INV-05 | Verified | Phase 1, 2 | `InvitationDeliveryTests.deliveryMintsATokenStoresOnlyItsHashAndSendsAFragmentLink` (only the SHA-256 is stored; a search for the raw token in the table finds nothing), `theOutboxEventNeverHoldsTheAddressOrTheTokenAndIsScrubbedOnceSent`, `InvitationSchemaTests` |
| AC-INV-06 | Pending | Phase 3, 4 | |
| AC-INV-07 | Pending | Phase 5 | |
| AC-INV-08 | Pending | Phase 4 | |
| AC-INV-09 | Pending | Phase 4 | |
| AC-INV-10 | Pending | Phase 4 | |
| AC-INV-11 | Pending | Phase 2, 4 | |
| AC-INV-12 | Pending | Mechanism in Phase 4; closed in slice 006 | |
| AC-INV-13 | Verified for creating, resending and revoking | Phase 3 | `InvitationApiTests.noInvitationOperationIsAllowedWhileTheCompanyIsPendingDeletion` (403 for each, nothing changes). Acceptance for a pending-deletion company comes in Phase 4 (AC-INV-09) |
| AC-INV-14 | Pending | Phase 4 | |
| AC-INV-15 | Verified for creation and resend; accept side in Phase 4 | Phase 1, 2, 3, 4 | `InvitationApiTests.anAddressThatIsAlreadyAMemberOrAlreadyInvitedIsAConflict` (409 `CONFLICT`), `resendingGivesANewExpiryAndRevokingMakesItFinal` (the old token is dead after the resend), `InvitationServiceTests.manyCreationsOfTheSameInvitationAtOnceLeaveExactlyOnePending`; that the previous token is rejected at acceptance is shown in Phase 4 |
| AC-INV-16 | Verified | Phase 3 | `InvitationApiTests.anAddressThatIsAlreadyAMemberOrAlreadyInvitedIsAConflict` (ACTIVE and SUSPENDED members, 409), `InvitationServiceTests...CannotBeInvitedButARemovedOneCan` |
| AC-INV-17 | Verified | Phase 2, 3 | `InvitationApiTests.anAdminInvitesAgentsAndViewersButNotAdminsAndNobodyInvitesAnOwner` (201, 201, 403, 400, 400), `InvitationRulesTest` |
| AC-INV-18 | Pending | Phase 4 | |
| AC-INV-19 | Verified at delivery; lookup in Phase 4 | Phase 1, 2, 4 | `InvitationDeliveryTests` (the token is 43 base64url characters from the existing 256-bit `TokenCodec`; `aRetriedDeliveryMintsANewTokenAndKillsThePreviousOne`), `InvitationSchemaTests` (unique hash, the lookup by hash) |
| AC-INV-20 | Pending | Phase 5 | |
| AC-INV-21 | Verified | Phase 2, 3 | `InvitationApiTests.resendingGivesANewExpiryAndRevokingMakesItFinal` (over HTTP: the old hash is gone when the response arrives), `InvitationServiceTests` |
| AC-INV-22 | Pending | Phase 4 | |
| AC-INV-23 | Verified | Phase 2, 3 | `InvitationApiTests.the21stInvitationByOneInviterIsThrottledWithRetryAfter` (429 `RATE_LIMITED` with `Retry-After`; another inviter is unaffected), `the4thSendToOneAddressWithin24HoursIsThrottled`; the company limit of 50 and the fail-closed 503 at service level in `InvitationServiceTests` and `InvitationFailClosedTests` |
| AC-INV-24 | Pending | Phase 4 | |
| AC-INV-25 | Pending | Phase 4 | |
| AC-INV-26 | Not exercised until plans exist (slice 006) | Phase 3, closed in slice 006 | The create path never asks about plan capacity: there is no code that could reject it, and `InvitationApiTests` creates invitations freely. The capacity warning is a screen concern (frontend slice F1). The criterion cannot be shown against a real plan before slice 006 |
| AC-INV-27 | Verified | Phase 3 | `InvitationApiTests.invitingAnAdminNeedsARecentLoginAndInvitingOthersDoesNot` and `anAdminCannotResendOrRevokeAnAdminInvitationAndResendingOneNeedsARecentLogin` (403 `REAUTHENTICATION_REQUIRED` after 11 minutes; other roles and revoking need none) |
| AC-INV-28 | Verified | Phase 1, 3 | `InvitationApiTests.anAddressThatIsAlreadyAMemberOrAlreadyInvitedIsAConflict` (a duplicate pending invitation is 409), `InvitationServiceTests.aSecondPendingInvitationForTheSameAddressIsRefusedUntilTheFirstExpires` |
| AC-INV-29 | Pending | Phase 4 | |

Also closed by this slice, for the consumer it supplies: AC-AUTH-14 (inviting an Admin), AC-TEN-08 for invitations. The screens beyond the accept page belong to frontend slice F1 (D-163) and are not part of these criteria.


## Phase 1 evidence (not tied to one AC)
- Migration `V13__create_invitation.sql`: a new table only. Checks: role (never Owner), status, normalized lower-case address, token hash format, a token only while pending, outcome fields match the status, expiry after creation; unique token hash; partial unique index on pending invitations per company and address; foreign keys RESTRICT (the purge in slice 007 must delete invitations first; `InvitationSchemaTests` proves a company with invitations cannot be deleted by accident). `MigrationV13OnExistingDataTest` applies V1 to V12 with data, then V13: rows survive, Flyway validates, the new table is empty and usable.
- `InvitationRepository` (no `findById`/`findAll`; the token-hash lookup is the one unscoped read and returns only a company ID, only for PENDING invitations); `TenantRepositoryScopeTest` still passes.
- `InvitationLocks` (company row first, then the invitation, same 500 ms test timeout and the same `SET LOCAL`, refuses to run outside a transaction, another company's invitation looks missing): `InvitationLocksTests` (4).
- `RateLimiter.current` (read-only count; allowed means one more attempt is within the limit): `JdbcRateLimiterTests` (3 new). Mutation checks: making the invitation unique index non-partial fails the history test; making `current` count fails the read-only test.


## Phase 2 evidence (not tied to one AC)
- `InvitationRules` (pure): who may invite or handle which role; permission before the request itself; recent authentication only for creating or resending an Admin invitation. `InvitationService` does what must happen before any lock (404 for an outsider, 403 for an Agent or Viewer, the inviter and company allowances); `InvitationTransactions` locks the company row, then the invitation, loads the caller's role fresh, decides, writes, audits and queues the email.
- Delivery (`InvitationEmails`, `InvitationEmailHandler`): the token is minted in a short transaction under the same locks, only if the invitation is still pending, unexpired and the company is active. The email carries the company name, the role and the expiry in a fixed text, a fragment link, and no inviter name or address.
- Mutation checks: removing the early refusal fails the no-queueing test; letting delivery ignore the invitation status fails the revoked-before-delivery test; replacing the flush after marking an expired invitation fails the replacement test with the unique-index error predicted in the design (Hibernate runs inserts before updates).
- Found while testing: a sent outbox event is scrubbed to `{}` (D-100), so a test that looked for the invitation ID after delivery found nothing and an earlier "consumed" check passed vacuously. Both now read the event by ID before and after delivery. Another: tests that shared fixed token-hash values collided in the shared database in a full run; every hash is now random.

## Checks run (Phase 2)
`./mvnw verify` ran 473 tests with 0 failures and 2 skipped. The contract and frontend did not change and were not re-run. Not run: Playwright E2E, CI jobs.

## Phase 3 evidence (not tied to one AC)
- Endpoints (contract first): `POST` and `GET /api/v1/companies/{companyId}/invitations`, `POST .../invitations/{invitationId}/resend`, `DELETE .../invitations/{invitationId}`. `contracts/openapi.yaml`, `schema.d.ts` and the Postman collection are aligned. The response type has no field for a token or its hash, and tests check the raw response text for the word "token".
- `InvitationApiTests` (16): the matrix over four roles; malformed input (bad address, missing, unknown or Owner role, an extra field, a 5,000-character address) is a 400 with a field error and creates nothing; stale login for Admin invitations; conflicts; pending deletion; cross-company invitation IDs are 404; malformed and shortened IDs are 404; CSRF on every changing route; the 21st invitation and the 4th send are 429 with `Retry-After`; audit rows hold no address; a denied caller leaves a denial record written by the handler.
- Mutation check: removing the security matcher for revoke fails the revoke test.
- Found while testing: the 'newest first' list order is only defined if the clock moves, because the creation time comes from the injected clock; the test now advances it.

## Checks run (Phase 3)
`./mvnw verify` ran 489 tests with 0 failures and 2 skipped. `npm run lint` in `contracts`, `gen:api` and the `apps/web` typecheck were run for the contract change. Not run: Playwright E2E, CI jobs. There is no BFF route or UI for these endpoints (D-162).
