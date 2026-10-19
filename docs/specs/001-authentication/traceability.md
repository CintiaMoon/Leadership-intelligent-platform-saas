# SPEC-AUTH Traceability (implementation status)

- Updated: 2026-10-19, after Phase 4 (password reset). All backend acceptance criteria now have tests; the browser parts are Phase 5.
- "Verified" means the test exists and was run and passed in `./mvnw verify` (Testcontainers PostgreSQL). Phases not yet built are listed as Pending. Nothing here has been independently reviewed yet (Phase 6).

| AC | Status | Tests |
|---|---|---|
| AC-AUTH-01 | Verified | `SignupFlowTests.invalidInputIsRejectedWithFieldErrorsAndNoAccount`, `passwordsOfAnyCharactersUpTo128CodePointsAreAccepted` |
| AC-AUTH-02 | Verified | `SignupFlowTests.signingUpWithAnExistingAddressIsIndistinguishableAndNotifiesTheOwner`, `responseForANewAddressEqualsTheResponseForAnExistingOne`, `concurrentSignupsForOneAddressCreateExactlyOneAccount`, `SchemaConstraintTests.emailsAreUniqueOnTheNormalizedForm` |
| AC-AUTH-03 | Verified | `VerifyEmailTests.concurrentAttemptsWithOneTokenHaveExactlyOneWinner`, `anExpiredTokenIsRejected`, `aTokenIsValidUntilItsExpiryInstant`; mutation check: removing the single-use condition fails 3 tests |
| AC-AUTH-04 | Mechanism verified | `SessionLifecycleTests.verifiedOnlyEndpointsRejectAnAccountThatIsNoLongerVerified` (test-only endpoint), `LoginTests.aDisabledOrUnverifiedAccountNeverGetsASession`. Closed by the consuming slices |
| AC-AUTH-05 | Verified | `LoginTests.aSuccessfulLoginOpensASessionWithAHardenedCookie` (30-minute idle interval, hardened cookie), `everyLoginGetsANewSessionIdentifierAndTheOldSessionIsDestroyed`, `aSessionIdentifierPlantedByAnAttackerIsNeverAdopted`, `concurrentLoginsForOneAccountEachGetTheirOwnSession` |
| AC-AUTH-06 | Verified | `SessionLifecycleTests.logoutDestroysTheSessionAndClearsTheCookie` |
| AC-AUTH-07 | Verified | `SessionLifecycleTests.logoutDestroys...`, `revokingAUserEndsEverySessionOfThatUserOnly`, `LoginTests.anExistingSessionDiesWhenTheAccountIsDisabled`; mutation check: disabling the account-status check fails 2 tests |
| AC-AUTH-08 | Verified | Counter store across two instances (`JdbcRateLimiterTests`); registration, resend, login, step-up and password-reset requests are limited |
| AC-AUTH-09 | Verified for every endpoint that exists | Pre-login: `PreLoginCsrfTests` (13), `PreLoginCsrfTest` (7). Authenticated: `SessionLifecycleTests.logoutNeedsTheSessionBoundCsrfToken`, `theSessionBoundCsrfTokenIsIssued...`, `rotatingTheIdentifier...`, `RecentAuthenticationTests.stepUpNeedsASessionAndACsrfToken` |
| AC-AUTH-10 | Verified for every endpoint that exists | `LogHygieneTests` (signup/verify, login/session/logout, and password-reset journeys; one caught a real token leak in TRACE logging) |
| AC-AUTH-11 | Verified for signup, login, resend | `EmailAddressTest` (30), `LoginTests.theEmailIsNormalizedAtLogin`, `ResendVerificationTests.resendMatchesTheNormalizedAddress`. Invitation matching: slice 004 |
| AC-AUTH-12 | Verified | `VerifyEmailTests.presentingTheSameTokenAgainIsRejectedAndChangesNothing` |
| AC-AUTH-13 | Verified | `LoginTests.everyFailureLooksTheSameToTheCaller`; one Argon2id verification per failure: `LoginTimingTests` |
| AC-AUTH-14 | Mechanism verified | `RecentAuthenticationTests.aSensitiveOperationNeedsALoginWithinTheLastTenMinutes` (9m59s, 10m00s allowed; 10m01s refused with `REAUTHENTICATION_REQUIRED`) on a test-only endpoint. Closed by slices 003, 005, 007 |
| AC-AUTH-15 | Verified | `SessionLifecycleTests.anIdleSessionExpiresAfterThirtyMinutesAndActivityKeepsItAlive` (idle time is moved in the database because Spring Session reads the system clock) |
| AC-AUTH-16 | Verified | `SessionLifecycleTests.aSessionOlderThanTwelveHoursIsRejectedHoweverActiveItWas`, `RecentAuthenticationTests.aSessionEndsTwelveHoursAfterLoginWhateverTheActivity`; mutation check: disabling the limit fails 2 tests |
| AC-AUTH-17 | Verified | `PasswordPolicyTest` (9), `SignupFlowTests.invalidInputIsRejectedWithFieldErrorsAndNoAccount` |
| AC-AUTH-18 | Verified | Registration (6th per IP), resend (4th per account, 11th per IP), login (6th failure per account and IP, 21st attempt per IP per minute), password reset (4th per account, 11th per IP): `SignupFlowTests`, `ResendVerificationTests`, `PasswordResetTests`, `LoginTests.theSixthFailedAttempt...`, `theTwentyFirstLoginAttempt...` |
| AC-AUTH-19 | Verified | `LoginTests.everyFailureLooksTheSameToTheCaller` (unverified, right password) |
| AC-AUTH-20 | Verified | `ResendVerificationTests.unknownVerifiedAndDisabledAddressesGetTheSameResponseAndNoEmail`, `everyAcceptedRequestQueuesTheSameWork...` |
| AC-AUTH-21 | Verified for signup, resend, login, password reset | `FailClosedTests`, `JdbcRateLimiterTests.storeFailureFailsClosedQuickly`. Invitation-token endpoints: slice 004 |
| AC-AUTH-22 | Mechanism verified | `RecentAuthenticationTests.activityAndRenewalNeverRefreshTheAuthenticationTime`, `stepUpWithTheRightPasswordRefreshesIt...` |
| AC-AUTH-23 | Mechanism verified | `SessionLifecycleTests.rotatingTheIdentifierKeepsTheSessionButNotTheOldIdentifierOrCsrfToken`. Closed by slices 003 and 005 |
| AC-AUTH-24 | Verified for every endpoint that exists | `NoStoreFilter`; `FoundationHttpTests`, signup, verify, login, logout, step-up, session tests |
| AC-AUTH-25 | Verified | `LoginTests.manyFailuresAcrossAddressesAddACappedDelayButNeverLockTheAccount`, `LoginThrottleTest` (cap at 15 minutes) |
| AC-AUTH-26 | Verified | `LoginTests.everyFailureLooksTheSameToTheCaller` (throttled by pair), `theTwentyFirstLoginAttempt...`; refusal without evaluating the password: `LoginTimingTests.aThrottledAttemptIsRefused...` |
| AC-AUTH-27 | Verified | `LoginTests.aPasswordHashMadeWithWeakerParametersIsUpgradedAtLogin`, `aFailedLoginNeverUpgradesTheHash`, `PasswordHasherTest` |
| AC-AUTH-28 | Verified at unit level | `PasswordHasherTest.whenAllSlotsAreBusy...`, `slotsAreReleasedAfterUse...` |
| AC-AUTH-29 | Verified | `LoginTests.everyFailureLooksTheSameToTheCaller` (disabled, right password), `aDisabledOrUnverifiedAccountNeverGetsASession` |
| AC-AUTH-30 | Verified | `PasswordResetTests.everyAddressGetsTheSameAnswerAndOnlyEligibleAccountsGetAnEmail` (unknown, unverified, disabled, verified: same status, body, headers; same work queued) |
| AC-AUTH-31 | Verified | `PasswordResetTests.aVerifiedAccountGetsAOneHourSingleUseLink...`, `aTokenWorksOnlyOnce`, `anExpiredTokenIsRefusedAndChangesNothing`, `aTokenIsStillValidJustBeforeItExpires`, `concurrentAttemptsWithOneTokenHaveExactlyOneWinner` (10 threads, one winner) |
| AC-AUTH-32 | Verified | `PasswordResetTests.aSuccessfulResetEndsEverySessionInvalidatesOtherTokensAndSendsANotice`; mutation check: removing the revocation and token invalidation fails the test |
| AC-AUTH-33 | Verified | `PasswordResetTests.aWeakNewPasswordIsRefusedAndTheTokenStaysUsable` (too short, breached, too long) |
| AC-AUTH-34 | Verified | `PasswordResetTests.theFourthRequestForOneAccountIsSilentlyNotSentButStaysNeutral`, `theEleventhRequestFromOneAddressIsRefusedWithRetryAfter` |
| AC-AUTH-35 | Verified | `PasswordResetTests.theTokenIsNeverTakenFromThePathOrTheQueryString`; log hygiene: `LogHygieneTests.thePasswordResetJourney...`; the link carries the token in the fragment (`SmtpEmailSenderTest.linksCarryTheTokenInTheFragmentOnly`, reset email test) |
| AC-AUTH-36 | Verified | `PreLoginCsrfTests` (login included) |
| AC-AUTH-37 | Verified | `VerifyEmailTests.unknownMalformedExpiredAndConsumedTokensAllLookTheSame`, `PasswordResetTests.unusableTokensAllGetTheSameAnswerWhateverThePassword` |
| AC-AUTH-38 | Verified | `PasswordPolicyTest`, `LoginTests.theLoginPasswordIsNormalizedLikeTheSignupPassword` |
| AC-AUTH-39 | Verified | `PasswordHasherTest`, `FoundationHttpTests.exhaustedHashingCapacityIsA503WithRetryAfter` |
| AC-AUTH-40 | Verified | `DisplayNameTest`, `SignupFlowTests` |

## Outbox and platform (ADR-003, ADR-004, D-83)
`OutboxWorkerTests` (10), `SmtpEmailSenderTest` (5), `LoginThrottleTest` (4), and `SessionSchemaDriftTest`, which fails if a Spring Session upgrade changes the schema that migration V6 vendors.

## Not run or not yet possible
- Load test of the rate-limit store (ADR-004 release gate), the Argon2id benchmark on the production task size (D-12), and the cookie check in a production-like environment (ADR-001): Phase 6.
- Independent security and code review: Phase 6.
- End-to-end browser tests: Phase 5.
