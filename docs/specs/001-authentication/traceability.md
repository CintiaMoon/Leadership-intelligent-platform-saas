# SPEC-AUTH Traceability (implementation status)

- Updated: 2026-10-19, after Phase 2 (accounts, email verification, outbox, signup).
- "Verified" means the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL). Phases not yet built are listed as Pending. Nothing here has been independently reviewed yet (Phase 6).

| AC | Status | Tests |
|---|---|---|
| AC-AUTH-01 | Verified | `SignupFlowTests.invalidInputIsRejectedWithFieldErrorsAndNoAccount`, `passwordsOfAnyCharactersUpTo128CodePointsAreAccepted` |
| AC-AUTH-02 | Verified | `SignupFlowTests.signingUpWithAnExistingAddressIsIndistinguishableAndNotifiesTheOwner`, `responseForANewAddressEqualsTheResponseForAnExistingOne`, `concurrentSignupsForOneAddressCreateExactlyOneAccount`, `SchemaConstraintTests.emailsAreUniqueOnTheNormalizedForm` |
| AC-AUTH-03 | Verified | `VerifyEmailTests.concurrentAttemptsWithOneTokenHaveExactlyOneWinner`, `anExpiredTokenIsRejected`, `aTokenIsValidUntilItsExpiryInstant`; mutation check: removing the single-use condition fails 3 tests |
| AC-AUTH-04 | Pending | Mechanism in Phase 3 (login is rejected for unverified accounts there); closed by the consuming slices |
| AC-AUTH-05, 06, 07 | Pending | Phase 3 |
| AC-AUTH-08 | Partial | Counter store holds across two instances under concurrency (`JdbcRateLimiterTests.limitHoldsAcrossTwoApplicationInstancesUnderConcurrency`); applied to registration and resend. Login and reset limits: Phases 3 and 4 |
| AC-AUTH-09 | Verified for pre-login endpoints | `PreLoginCsrfTests` (13), `PreLoginCsrfTest` (7). Session-bound CSRF for authenticated requests: Phase 3 |
| AC-AUTH-10 | Verified for signup, verify, and the BFF path | `LogHygieneTests` (including the full signup and verification journey; it caught and led to the fix of a token leak in a debug log of the CSRF response); mutation check on the BFF credential log |
| AC-AUTH-11 | Partial | `EmailAddressTest` (30), `SignupFlowTests` normalization, `ResendVerificationTests.resendMatchesTheNormalizedAddress`. Login and invitation matching: Phase 3 and slice 004 |
| AC-AUTH-12 | Verified | `VerifyEmailTests.presentingTheSameTokenAgainIsRejectedAndChangesNothing` |
| AC-AUTH-13, 19, 26, 29 | Pending | Phase 3 |
| AC-AUTH-14, 22, 23 | Pending | Mechanism in Phase 3; closed by slices 003, 005, 007 |
| AC-AUTH-15, 16 | Pending | Phase 3 |
| AC-AUTH-17 | Verified | `PasswordPolicyTest` (9), `SignupFlowTests.invalidInputIsRejectedWithFieldErrorsAndNoAccount` |
| AC-AUTH-18 | Partial | Registration (6th from one IP) `SignupFlowTests.registrationIsLimitedToFivePerClientAddressPerHour`; resend (4th per account, 11th per IP) `ResendVerificationTests`. Login and reset limits pending |
| AC-AUTH-20 | Verified | `ResendVerificationTests.unknownVerifiedAndDisabledAddressesGetTheSameResponseAndNoEmail`, `everyAcceptedRequestQueuesTheSameWorkWhetherOrNotTheAccountExists` |
| AC-AUTH-21 | Partial | Signup and resend: `FailClosedTests`; counter store: `JdbcRateLimiterTests.storeFailureFailsClosedQuickly`; HTTP mapping: `FoundationHttpTests`. Login, reset, invitation: later phases |
| AC-AUTH-24 | Verified for the endpoints that exist | `NoStoreFilter` via `FoundationHttpTests`, `SignupFlowTests`, `VerifyEmailTests`, `PreLoginCsrfTests` |
| AC-AUTH-25, 27 | Pending (27 partial) | Hash records its parameters and `needsUpgrade` is tested (`PasswordHasherTest`); the re-hash at login is Phase 3 |
| AC-AUTH-28 | Verified at unit level | `PasswordHasherTest.whenAllSlotsAreBusy...`, `slotsAreReleasedAfterUse...` |
| AC-AUTH-30 to 35 | Pending | Phase 4 |
| AC-AUTH-36 | Verified | `PreLoginCsrfTests` |
| AC-AUTH-37 | Verified | `VerifyEmailTests.unknownMalformedExpiredAndConsumedTokensAllLookTheSame` |
| AC-AUTH-38 | Verified | `PasswordPolicyTest`, `SignupFlowTests.anAccountCreatedWithADecomposedPasswordMatchesTheComposedForm` |
| AC-AUTH-39 | Verified | `PasswordHasherTest`, `FoundationHttpTests.exhaustedHashingCapacityIsA503WithRetryAfter` |
| AC-AUTH-40 | Verified | `DisplayNameTest`, `SignupFlowTests` |

## Outbox and platform (ADR-003, ADR-004)
`OutboxWorkerTests` (10): exactly-once delivery with four concurrent workers, retry with backoff, FAILED after the last attempt, recovery after a worker crash, no loss on email failure, idempotent enqueue, rollback with the business transaction. `SmtpEmailSenderTest` (5) uses an in-process SMTP server.

## Not run or not yet possible
- Load test of the rate-limit store (ADR-004 release gate), the Argon2id benchmark on the production task size (D-12), and the cookie check in a production-like environment (ADR-001): Phase 6.
- Independent security and code review: Phase 6.
- End-to-end browser tests: Phase 5.
