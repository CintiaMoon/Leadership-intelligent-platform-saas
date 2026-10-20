# SPEC-AUTH Traceability (implementation status)

- Updated: 2026-10-20, after Phase 6 (merged in PRs #17 to #22). Earlier: 2026-10-19, after Phase 5 (web UI). All backend acceptance criteria have tests; the browser flows are covered by Playwright end-to-end tests (see the last section).
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

## Browser and BFF (Phase 5)
Unit (Vitest, 18): `bff.test.ts` (header and cookie allowlists, unlisted operations, Origin, Sec-Fetch-Site, content type, body limit, Set-Cookie and Retry-After passthrough, 503 without details, pre-login token without session cookie), `messages.test.ts` (one login failure message, field errors, throttle text), `forms.test.tsx` (CSRF header, fixed notices only, field errors, navigation).
End-to-end (Playwright, 8, real BFF, API, PostgreSQL, Mailpit), `e2e/auth.spec.ts`:
| Behavior | AC | Test |
|---|---|---|
| Signup, verification by emailed link, login, profile, logout; unverified login looks like a wrong password; cookie is HttpOnly and SameSite=Lax and not readable by scripts; logout clears it | AC-AUTH-02, 05, 06, 19 | `signup, email verification, login, profile and logout` |
| Verification link works once, then offers a new link | AC-AUTH-12, 37 | `a verification link works once` |
| Resend answers the same for any address | AC-AUTH-20 | `the resend form gives the same answer for any address` |
| Reset: neutral request, token leaves the address bar and never appears in a request URL, breached password refused with the link still usable, other browser signed out, old password fails, link spent, notice email sent | AC-AUTH-30 to 35, 37 | `password reset: neutral request, policy check, one-time link, sessions revoked` |
| BFF refuses cross-origin and token-less POSTs and unlisted operations | AC-AUTH-36 | `the BFF refuses a state-changing request ...` |
| Pages without a token explain it | AC-AUTH-37 | `a link without a token explains itself` |
| No serious or critical axe violations (WCAG 2 A, AA, 2.2 AA tags) | n/a | two accessibility tests |
Mutation check: leaving the token in the address bar fails 3 end-to-end tests.

## Phase 6 (hardening) results, 2026-10-19
- **Argon2id benchmark** (`Argon2BenchmarkTest`, opt-in with `-Dbenchmark=true`): run on a developer laptop only (8 CPUs, Java 24). It does not give the D-12 values, because the production task size is not fixed. See plan section 14 for the numbers and the procedure for the target machine.
- **Rate-limit store load test** (`RateLimitLoadTest`, opt-in): 64 threads, local PostgreSQL 17 in Docker. Exactly 100 of 25,600 hits on one hot key were allowed; the same holds across 8 separate limiter instances sharing the database (100 of 3,200). 5,600 writes per second with a pool of 4 (p99 61 ms); a 200,000-row expired backlog was swept in 445 ms. The fail-closed path under pool exhaustion was not reached (no request exceeded the 250 ms wait), so it is still covered only by `FailClosedTests` and `JdbcRateLimiterTests`.
- **Cookies as in production** (`E2E_PROD_COOKIES=true npm run e2e`): all 8 end-to-end tests pass with `__Host-` names and `Secure` cookies on `http://localhost` in Chromium, which accepts Secure cookies there. This is not a real HTTPS edge or routing environment; that check is still open.
- **Independent reviews** (security-engineer and code-reviewer agents, read-only, nothing executed): findings and their triage are in plan section 14. Corrections to this file they prompted: AC-AUTH-39 is verified for the error mapping and the semaphore, not for real signup, login or reset under saturation; AC-AUTH-18 is satisfied under the D-78 reading (the sixth failure is refused for one minute); the "a link without a token explains itself" test is about the page, not AC-AUTH-37.

- **Code fixes from the reviews** (plan section 15): `EncodedPathTests`, `CredentialsVersionTests`, and the strengthened burst test in `LoginTests` (AC-AUTH-18) were added. Mutation check: removing the per-pair lock makes the burst test see 12 counted failures instead of 5.

- **Decisions D-95 to D-101** (plan section 16): AC-AUTH-41 `SignupFlowTests.aLaterSignupTakesOverAnAccountThatWasNeverVerified`, `AccountRegistrar` audit row; AC-AUTH-42 `ResendVerificationTests.atMostThreeLinksAreValidAtOnceAndTheOldestIsRetiredFirst`, `anUnverifiedAccountGetsAFreshTokenAndAnEarlierLinkStaysValid`, `PasswordResetTests.aStrangerRequestingAnotherLinkDoesNotCancelTheOwnersPendingOne`; AC-AUTH-25 and 43 `LoginTests.manyFailuresAcrossAddressesAddACappedDelayButNeverLockTheAccount` (100), `aKnownDeviceIsNotHeldOutByTheAccountWideCeilingButAStrangerIs`, `aKnownDeviceIsStillBlockedByTheAccountAndAddressLimit`; AC-AUTH-44 `RetentionTests` (mutation check: removing the payload scrub fails `aDeliveredEventKeepsNoAddress`); D-95 `bff.test.ts` (client address) and `TrustedProxyGuardTest`. AC-AUTH-39 now also through the real endpoints (`HashSaturationTests`), AC-AUTH-21 for the login throttle and step-up (`FailClosedLoginThrottleTests`).
- Checks run for this part: `./mvnw verify` 255 tests, 0 failures (2 opt-in skipped); web lint, typecheck, 20 unit tests, build; Playwright 8/8 in both cookie modes. Not run: the CloudFront and ALB setup that D-95 depends on.

- **Part 4 (PR #22)**: Argon2id 64 MiB, t=4, p=1, cap 2 (D-96, approved; `PasswordHasherTest.theProductionDefaultsAreTheValuesApprovedInD96`); startup guards (`SecretQualityTest`, `PreLoginCsrfTest`); nonce-based CSP, HSTS, Permissions-Policy, COOP (`proxy.test.ts`, e2e test "pages carry a nonce-based policy and every script has the nonce", and every e2e test fails on any CSP violation); CI supply chain (CodeQL, dependency review, `npm audit`, pinned images and linter). CI found a race in the signup takeover (D-97), fixed in `SignupService`; `SignupFlowTests.concurrentSignupsForOneAddressCreateExactlyOneAccount` now expects one account and one to eight verification emails.
- Checks run for part 4: `./mvnw verify` 261 tests, 0 failures (2 opt-in skipped); web lint, typecheck, 22 unit tests, build, `npm audit` (0 vulnerabilities); Playwright 9/9 in both cookie modes; all CI jobs green on PR #22 (api, web, e2e, contract, secret-scan, dependency-review, CodeQL).
- Side effect: `cacheComponents` and `partialPrefetching` are off and all pages render per request (D-102), because prerendered scripts carried no nonce.

## Slice 001 closure status (2026-10-20)
All 44 acceptance criteria have tests. AC-AUTH-04, 14, 22 and 23 are verified as mechanisms on test-only endpoints; the consuming slices (003, 005, 007) close them. Slice 001 is **not closed**: the items below need a decision or work outside the repo.

| Open item | Needed from | Blocks closure? |
|---|---|---|
| Argon2id values re-measured on the real task size (D-96) | DevOps / user: task size | Before production |
| CloudFront / ALB setup and client-address chain (D-95) verified in a real environment | DevOps | Before production |
| Cookie, CSP and HSTS behaviour through a real HTTPS edge; Firefox, WebKit, real devices; CSP on the dev server; violation reporting | Test owner | Before production |
| Runbooks rehearsed: secret rotation, operator account disable, session revocation | DevOps, security owner | Before production |
| Manual keyboard-only and screen-reader review of the auth pages | Accessibility reviewer | Yes (accessibility) |
| D-87 consent recording and retention periods (D-100) | Legal review | Before production |
| D-08 AWS / SES cost check | User | Before production email |
| Product-owner confirmations from Phase 5 (D-92 omissions, brand tagline) | Product owner | No |
| Per-request rendering (D-102) accepted | User | No |
| Closure sign-off | User | Final step |

## Not run or not yet possible
- Argon2id values on the production task size (D-12) and the rate-limit load test against the production database class.
- The cookie and client-address checks through a real HTTPS edge (ADR-001).
- Playwright tests ran in Chromium only (locally and in the CI `e2e` job). Firefox, WebKit, and real mobile devices were not tested.
- Manual keyboard-only and screen-reader review of the auth pages: not done. Automated axe scans pass for serious and critical issues, which is not proof of accessibility.
- Runbooks are drafts and have not been rehearsed.
