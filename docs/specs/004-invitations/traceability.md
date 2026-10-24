# SPEC-INV Traceability (implementation status)

- Updated: 2026-10-24, after Phase 2 (rules, service, delivery). The rules, the service and the delivery of the email are tested; nothing is exposed over HTTP yet, so most criteria are "Verified at service level" until Phase 3.
- "Verified" will mean the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL), and for AC-INV-20 and the accept flow, in the web unit tests and Playwright.
- Plan: `plan.md`. Decisions: D-144 to D-163. Spec approved 2026-10-24 with AC-INV-27 to AC-INV-29 added.

| AC | Status | Planned in | Tests |
|---|---|---|---|
| AC-INV-01 | Verified at service level; HTTP in Phase 3 | Phase 2, 3 | `InvitationServiceTests.anAgentAndAViewerAreRefusedBeforeAnyLockAndBeforeAnythingIsCounted`, `anOutsiderGetsNotFoundAndCannotUseUpTheCompanysAllowance`, `InvitationRulesTest` (Agent and Viewer refused for every operation and role); mutation check: removing the early refusal fails it |
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
| AC-INV-13 | Verified at service level; HTTP in Phase 3 | Phase 3 | `InvitationServiceTests.noInvitationOperationIsAllowedWhileTheCompanyIsPendingDeletion` (create, resend and revoke all refused), `InvitationRulesTest` |
| AC-INV-14 | Pending | Phase 4 | |
| AC-INV-15 | Verified at service level; HTTP in Phase 3 | Phase 1, 2, 3 | `InvitationServiceTests.manyCreationsOfTheSameInvitationAtOnceLeaveExactlyOnePending` (8 parallel creates), `aSecondPendingInvitationForTheSameAddressIsRefusedUntilTheFirstExpires`, `resendingClearsTheTokenStartsANewExpiryQueuesAnEmailAndAuditsIt`, `InvitationDeliveryTests.aResendDeliversAFreshTokenAndTheEarlierOneStopsWorking` |
| AC-INV-16 | Verified at service level; HTTP in Phase 3 | Phase 3 | `InvitationServiceTests.anAddressThatIsAlreadyAnActiveOrSuspendedMemberCannotBeInvitedButARemovedOneCan` (also matches on the normalized address) |
| AC-INV-17 | Verified at service level; HTTP in Phase 3 | Phase 2, 3 | `InvitationRulesTest.ac17TheRoleGrantTable` and `everyCombinationMatchesTheSpecStatement` (240 combinations against a second statement of the rules), `InvitationServiceTests.anAdminInvitesAgentsAndViewersButNotAdminsAndNobodyInvitesAnOwner` |
| AC-INV-18 | Pending | Phase 4 | |
| AC-INV-19 | Verified at delivery; lookup in Phase 4 | Phase 1, 2, 4 | `InvitationDeliveryTests` (the token is 43 base64url characters from the existing 256-bit `TokenCodec`; `aRetriedDeliveryMintsANewTokenAndKillsThePreviousOne`), `InvitationSchemaTests` (unique hash, the lookup by hash) |
| AC-INV-20 | Pending | Phase 5 | |
| AC-INV-21 | Verified at service level; HTTP in Phase 3 | Phase 2, 3 | `InvitationServiceTests.resendingClearsTheTokenStartsANewExpiryQueuesAnEmailAndAuditsIt` and `revokingMakesItFinalClearsTheTokenAndIsAudited` (the old token stops working inside the request's own transaction) |
| AC-INV-22 | Pending | Phase 4 | |
| AC-INV-23 | Verified at service level; HTTP in Phase 3 | Phase 2, 3 | `InvitationServiceTests.the21stInvitationByOneInviterWithinAnHourIsRejected`, `the51stInvitationForOneCompanyWithinADayIsRejected`, `the4thSendToOneRecipientWithin24HoursIsRejectedAndResendsCount`, `InvitationFailClosedTests` (the store down: 503 and nothing changes) |
| AC-INV-24 | Pending | Phase 4 | |
| AC-INV-25 | Pending | Phase 4 | |
| AC-INV-26 | Pending | Phase 3 | |
| AC-INV-27 | Verified at service level; HTTP in Phase 3 | Phase 3 | `InvitationServiceTests.invitingAnAdminNeedsARecentLoginAndNothingIsCreatedWithoutOne`, `anAdminCannotResendOrRevokeAnAdminInvitationAndResendingOneNeedsARecentLogin`, `InvitationRulesTest.ac27...` |
| AC-INV-28 | Verified at service level; HTTP in Phase 3 | Phase 1, 3 | `InvitationServiceTests.aSecondPendingInvitationForTheSameAddressIsRefusedUntilTheFirstExpires` (409, then an expired one is marked EXPIRED, audited, and a new one succeeds; mutation check: without the flush the unique index fails), `InvitationSchemaTests` |
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
