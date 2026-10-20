# Implementation Plan: SPEC-AUTH (slice 001)

- Status: Approved 2026-10-19 (P-01 to P-17 and the phase order, by the user). P-xx maps to D-(74+xx) in the decision register. Phase 0 in progress.
- Spec: `docs/specs/001-authentication/spec.md` (SPEC-AUTH, Approved 2026-10-17)
- Related ADRs: ADR-001 (session and BFF boundary, Proposed), ADR-003 (outbox), ADR-004 (rate limiting), ADR-005 (errors), ADR-006 (frontend tooling), ADR-002 (build baseline)
- Date: 2026-10-19

## 1. Readiness check (CLAUDE.md workflow step 1)
Decisions that block slice 001 (D-01 to D-06, D-09, D-11 to D-16, D-51, D-55, D-56) are all **Decided**. None is Open. Items that are not Open but still need attention:

| Item | State | Action in this plan |
|---|---|---|
| ADR-001 | Proposed; CSRF flow "documented, not tested"; D-08 topology only partly final | Accept it in Phase 0 once decision P-01 (pre-login CSRF) is made. Final cookie verification in a production-like environment stays a release gate |
| D-12 final Argon2id values | Security approval of benchmarked values pending | Start at the OWASP floor (m=19 MiB, t=2, p=1), mark provisional, and benchmark in Phase 6 before any deployment |
| D-08 AWS cost check | Open, but it does not gate code | Not in scope; the email adapter is SMTP-first so SES wiring waits |
| D-18 security-owner sign-off | Not recorded | Not a slice 001 dependency (002 onward) |

## 2. Assumptions and trust boundaries
- Browser, BFF (Next.js), API (Spring Boot), PostgreSQL, SMTP/SES. The browser is untrusted. The BFF is a proxy and is authenticated to the API by a shared secret (D-50). The API is the only authority for identity, sessions, and verification state.
- Client-supplied user IDs, roles, verification state, forwarded headers, and `Origin` are never trusted by the API on their own.
- Email and any outbound call happen after commit through the outbox (CLAUDE.md principle 10).
- The base URL used in emailed links comes from configuration, never from the `Host` header (prevents reset-link poisoning).

## 3. Gaps found, with proposed best-practice answers
These are **proposals for your approval**. Each becomes a decision (next free IDs from D-75) in the register and, where it changes behavior, an ADR or spec edit. Nothing here changes an approved requirement; each fills a hole the spec leaves open.

| ID | Gap | Proposal (industry practice) | Owner |
|---|---|---|---|
| P-01 🔒 | The spec says the BFF fetches a session-bound CSRF token, but signup, login, verify, resend, and reset happen **before a session exists** | Authenticated requests: Spring Security synchronizer token bound to the session (`HttpSessionCsrfTokenRepository`, BREACH-safe `XorCsrfTokenRequestAttributeHandler`), re-issued on session-id rotation, as in ADR-001. Pre-login POSTs: a stateless signed double-submit token (HMAC of a random value with a server key, OWASP "signed double-submit cookie"), plus the BFF `Origin`/`Sec-Fetch-Site` check and `Content-Type: application/json` only. Do not create anonymous DB sessions: that is a cheap DoS | Security owner |
| P-02 🔒 | "Compromised-password blocklist" has no source | Bundle a vetted offline list (top ~100k from a public breach corpus) loaded into memory, checked on the normalized password. Offline is deterministic, testable, and free of third-party calls and privacy leakage. Keep a Pwned Passwords k-anonymity check as a later ADR (NIST SP 800-63B and OWASP both accept either) | Security owner |
| P-03 | Maximum password length and Unicode handling | Accept 8 to 128 characters (spec requires at least 64; the cap bounds hashing cost), apply Unicode NFKC normalization before hashing and before the policy check, never trim or truncate, count code points. Argon2 has no 72-byte limit like bcrypt | Security owner |
| P-04 🔒 | "Progressive delay" is undefined, and a server-side `sleep` would let attackers tie up threads | Implement it as an **escalating block window**, not a sleep: after the 5th failure in 15 minutes, further attempts for that (account, IP) key are refused for 1, 2, 4, 8, 15 minutes (cap 15). While blocked the API does not evaluate the password and returns the same generic login failure (AC-AUTH-26). Account-only ceiling (20 per hour) adds the capped window per D-51 and never locks | Security owner |
| P-05 | Signup duplicate-account behavior leaks via timing and email | Return the identical `202` neutral response for new and existing emails and do all differing work after commit in the outbox: new account gets a verification email; existing account gets an "an attempt was made to register" email. Equalize the synchronous work (hash the password in both paths) | Architect |
| P-06 | Login timing and state oracles | Always run one Argon2id verification, using a pre-computed dummy hash for unknown, unverified, or DISABLED accounts. Single generic failure code and body for unknown, wrong password, unverified, disabled, and throttled (AC-AUTH-13, 19, 26, 29) | Architect |
| P-07 | Token format and error granularity | 256-bit `SecureRandom`, base64url. Store SHA-256 of the token (high entropy makes a fast hash correct here; Argon2 is for passwords). Consume with one conditional statement `UPDATE ... SET consumed_at = now() WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > now() RETURNING user_id` so concurrent use has one winner. Return one generic `TOKEN_INVALID` code for unknown, expired, and consumed tokens (no oracle); the UI offers resend | Architect |
| P-08 🔒 | Spring Session gives idle timeout but not the 12-hour absolute limit or last-authentication time | Store `createdAt` and `lastAuthenticatedAt` as session attributes; a filter rejects and invalidates sessions older than 12 h regardless of activity. `lastAuthenticatedAt` is written only by login and step-up (AC-AUTH-22). Principal name is the user UUID, so "revoke all sessions of user X" uses `FindByIndexNameSessionRepository`. The filter also re-checks that the account is ACTIVE on each request (invariant 10). Non-persistent browser cookie plus server-side enforcement; no "remember me" in V1 | Security owner |
| P-09 | Session tables vs "Flyway owns the schema" | Copy Spring Session's PostgreSQL DDL into a Flyway migration, set `spring.session.jdbc.initialize-schema=never`, add a scheduled cleanup, and keep a test that fails if the vendored DDL differs from the library version after an upgrade | Architect |
| P-10 | Email delivery for local, CI, and E2E | A narrow `EmailSender` port with an SMTP adapter now (works with Mailpit locally and CI E2E, and with SES SMTP later). SES API adapter is deferred to the deployment slice. Add Mailpit to `infra/local/compose.yaml` | Architect |
| P-11 | Hash concurrency cap (AC-AUTH-28) has no number or overload behavior | Bounded semaphore sized from the task memory (permits = floor(usable heap budget / ~64 MiB per hash with headroom), initially 4 on a 2 GiB task), wait at most 2 seconds, then return 503 with `Retry-After`, counted by a metric. The final size comes from the Phase 6 benchmark | Security owner |
| P-12 | Rate-limit counter details (ADR-004) | Fixed-window counters, key = HMAC-SHA-256 of the normalized identifier and IP (no raw emails or IPs at rest), atomic `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`, a dedicated small Hikari pool with a 250 ms timeout (that timeout defines "store unavailable"), in-memory Caffeine limiter in front, expiry sweep job. Throttled neutral endpoints (resend, reset) still return the neutral response and send nothing; IP-only limits may return 429 | Security owner |
| P-13 | Signup fields are not specified | Needs the product owner. Recommendation: `email`, `password`, and a required `displayName` (1 to 100 characters after trim, not unique). Avoid collecting more. Whether a terms/privacy acceptance must be recorded depends on your legal basis for processing (for example LGPD or GDPR); the spec is silent | Product owner |
| P-14 | Audit events for auth are catalogued but have no acceptance criterion | Include an append-only `audit_event` table and the auth events (`auth.user.registered`, `auth.email.verified`, `auth.login.succeeded`, `auth.login.failed`, `auth.session.revoked`, `auth.password.reset_*`, `auth.step_up.succeeded`). To avoid attacker-driven write floods, record `auth.login.failed` only for existing accounts and count the rest as metrics. Database-role hardening (separate migrator and runtime roles with insert-only grants) is documented now and enforced in the deployment slice | Architect |
| P-15 | Recent-authentication (AC-AUTH-14, 22), "verified-only" actions (AC-AUTH-04), and role-change rotation (AC-AUTH-23) have no consumers until slices 002 to 007 | Build the mechanism now (a `RecentAuthentication` authorization requirement, a `POST /api/v1/auth/step-up` password endpoint, and a `SessionRotationService`) and test it with a test-only controller. These ACs are marked **mechanism verified** in 001 and **closed** when their consuming slice lands | Architect |
| P-16 | Request correlation and log hygiene (AC-AUTH-10) | A correlation-ID filter (generated, never trusted from the client for authority), MDC in log pattern, and a log-capture test proving passwords, cookies, session IDs, and raw tokens never appear, plus request-body and `Cookie`/`Set-Cookie` header masking in any access or debug logging | Security owner |
| P-17 | Per-IP limits depend on trusted forwarded headers (ADR-001) | A filter authenticates the BFF with the shared secret (constant-time comparison, two secrets valid during rotation); the client address is taken from the BFF-supplied forwarded header only after that check, and ignored otherwise. CIDR allowlist for proxies is configuration with a documented local default | Security owner |

## 4. Phases (one PR each, in order; each leaves `main` green)
Each phase starts with its OpenAPI additions (contract-first), then migration, backend, tests. Only one phase touches the migration folder, `SecurityConfig`, or `openapi.yaml` at a time, per CLAUDE.md.

### Phase 0: Governance (docs only)
- Approve P-01 to P-17; record them as D-75 onward in the decision register; update SPEC-AUTH where behavior changes (P-04, P-05, P-07, P-11, P-13).
- Accept ADR-001 (with the CSRF design from P-01) and ADR-004 amendments (P-12).
- OpenAPI: add the problem+json schema (ADR-005), security schemes, and all auth paths with CSRF, no-store, rate-limit, and error responses.

### Phase 1: Platform foundations
- Dependencies: `spring-session-jdbc`, BouncyCastle (Spring's `Argon2PasswordEncoder` needs it), Caffeine.
- Error handling (problem+json with stable `code`), correlation ID, log redaction, BFF-trust and client-IP filter, `Cache-Control: no-store` for `/api/v1/auth/**`, injected `Clock`.
- Rate-limit store (counter migration, dedicated pool, limiter service, sweep job).
- Tests: two application instances share limits, store-down fails closed, spoofed `X-Forwarded-For` ignored, log-capture test.

### Phase 2: Accounts and email verification
- Flyway: `app_user` (UUID, `email_normalized` unique with a lowercase check, `email_original`, password hash, `status` ACTIVE/DISABLED, `email_verified_at`, timestamps, version), `verification_token`, `outbox_event`, `audit_event`.
- Domain: email normalization (AC-AUTH-11), password policy, hashing service with dummy-hash and permit cap, token service.
- Outbox worker (`FOR UPDATE SKIP LOCKED`, retry limit, failed-visible) and the `EmailSender` SMTP adapter with templates.
- Endpoints: signup, verify-email, resend-verification.
- Tests (Testcontainers PostgreSQL): concurrent token consumption, duplicate-signup indistinguishability, expiry, replay, outbox crash/retry, concurrent workers.

### Phase 3: Login, sessions, logout, CSRF, step-up
- Flyway: Spring Session tables (P-09).
- Spring Security chain: session policy, rotation on login, absolute-age and account-status filter, CSRF per P-01, logout that revokes and clears the cookie, `GET /api/v1/auth/session`, `POST /api/v1/auth/step-up`.
- Argon2 parameter upgrade on login (AC-AUTH-27), login throttling per P-04.
- Tests: fixation, rotation, idle and absolute expiry using the injected clock, revocation, CSRF-valid and invalid, disabled account, unverified login, enumeration parity (status, body, code, and a coarse timing check).

### Phase 4: Password reset
- Flyway: `password_reset_token` (or reuse `verification_token` with a purpose column; decided in the Phase 2 migration review).
- Endpoints: request and confirm. Token in the URL fragment and request body only. Revoke all sessions, invalidate other tokens, send notification email.
- Tests: neutral response matrix, concurrency, expiry, session revocation, throttles (AC-AUTH-30 to 35).

### Phase 5: Next.js BFF and UI
- Route Handlers that forward an allowlist of headers, pass `Set-Cookie` through, set `no-store`, and check `Origin`; no second session store (ADR-001, no Auth.js).
- Pages: signup, check-your-email, verify-email (token read from the fragment), login, logout, forgot and reset password, session-expired handling. Accessible forms, per the ui-design skill. Client types generated from the OpenAPI file.
- Playwright E2E through BFF to API with Mailpit: signup to verified login, logout, expiry, reset. A new CI job runs it.

### Phase 6: Hardening and closure
- Benchmark Argon2id on the target task size and record values for security approval (D-12); load test the rate-limit store (ADR-004 release gate); run the independent security-engineer and code-reviewer reviews; verify the cookie behavior in a production-like routing environment.
- Update traceability (each AC mapped to a test), `docs/runbooks` (secret rotation, operator account disable, session revocation), and list any check not run.

## 5. Expected files (summary)
- Backend: `services/api/src/main/java/com/example/saas/api/{auth,security,ratelimit,outbox,email,audit,common}/`, migrations `V1` to `V6` under `db/migration`, `application.yml` additions.
- Contract: `contracts/openapi.yaml`.
- Frontend: `apps/web/src/app/(auth)/…`, `apps/web/src/app/api/…` route handlers, `apps/web/src/lib/api/`, Playwright config and tests.
- Infra and CI: Mailpit in `infra/local/compose.yaml`, an E2E job in `.github/workflows/ci.yml`.
- Docs: decision register, SPEC-AUTH, ADR-001 and ADR-004 updates, traceability, runbooks.

## 6. Acceptance-criteria coverage
AC-AUTH-01 to 35 map to the phases above: 01, 02, 03, 11, 12, 17, 20 in Phase 2; 05 to 07, 09, 13, 15, 16, 19, 22 (mechanism), 24, 26, 27, 29 in Phase 3; 08, 18, 21, 25, 28 in Phases 1 and 3; 10 in Phase 1; 30 to 35 in Phase 4. AC-AUTH-04, 14, and 23 are verified at mechanism level in this slice and closed by their consuming slices.

## 7. Risks
- Rate-limit writes on the primary database add load; mitigated by the in-memory front limiter, separate pool, and the Phase 6 load test.
- Argon2 memory per hash versus task size: capped by P-11; revisit with the benchmark.
- Pre-login CSRF design (P-01) is the most security-sensitive choice and needs security-owner approval before Phase 3.
- Slice size: SPEC-AUTH is large; the phases keep each PR reviewable but the slice is not closed until Phase 6.

## 8. Checks that will be run per phase
`./mvnw verify` (Testcontainers PostgreSQL), web lint, typecheck, tests, build, Redocly contract lint, Playwright E2E (Phase 5 on), gitleaks in CI. Anything not run will be listed.

## 9. Approval needed
1. Approve, change, or reject each of P-01 to P-17 (P-01, P-02, P-04, P-08, P-11, P-12, and P-16 to P-17 are security decisions; P-13 is the product owner's).
2. Approve the phase order and one-PR-per-phase approach.
3. Confirm that I may then record the decisions and start Phase 0.

## 10. Implementation notes (Phase 2, 2026-10-19)
Deviations and additions found while building; none changes an approved requirement.
- **Pre-login CSRF moved into Phase 2.** The signup, verify, and resend endpoints need CSRF protection from the day they exist, so the stateless signed double-submit token (D-75), `GET /api/v1/auth/csrf`, and the filter were built here instead of Phase 3. Phase 3 adds the session-bound token for authenticated requests.
- **No token or secret is stored in the outbox.** Outbox payloads hold identifiers only (user ID or normalized email); the verification token is minted when the worker delivers the email, so the raw token exists only in the sent message. This replaces the idea of encrypting a token in the payload.
- **Resend does its lookup in the worker.** Every accepted resend writes the same outbox record, whether or not an account exists, which removes the timing difference (AC-AUTH-20).
- **Added limit (to confirm):** `signup-account`, 3 per normalized email per 24 hours, applied to new and existing addresses alike, so the "account already exists" email cannot be used to flood a victim. The spec does not set it; the value is a proposal.
- **Blocklist size:** the bundled list has 46,948 entries (the SecLists top 100,000, keeping entries of 8 or more characters, since shorter ones fail the length rule anyway). It is a starting point; refreshing it is an operational task.
- **Mail health check disabled.** Email goes through the outbox, so a mail-server outage must not mark the API unhealthy.
- **Unknown JSON fields are rejected** (`additionalProperties: false` in the contract).
- **Audit trigger blocks UPDATE only.** Restricting DELETE and TRUNCATE needs database roles, which are deferred to the deployment slice as planned (D-88).
- **Logging finding:** the log-hygiene test found that Spring's TRACE logging of the CSRF response body would have printed the token; the response record now redacts itself.

## 11. Implementation notes (Phase 3, 2026-10-19)
- **Session creation time is Spring Session's own.** D-82 listed a `createdAt` session attribute; the session already persists its creation time, which login resets by creating a new session, so the 12-hour check uses that. Same behavior, one fewer attribute to keep consistent.
- **Spring Security never persists its own security context.** A filter turns the session into the request's authentication on every request and re-checks that the account is ACTIVE and verified. It must also register the context with the request-attribute repository: without that, Spring Security's session management treats each request as a new login and rotates the session ID and CSRF token every time (found by the first test run; covered by `SessionLifecycleTests`).
- **The session cookie is defined by our own bean, not by `server.servlet.session.cookie.*`.** Spring Boot applies those settings only with an embedded web server, so they were not visible to tests; the explicit serializer makes the cookie identical in tests and production (`__Host-session`, HttpOnly, Secure, SameSite=Lax, Path=/, no Domain, no Max-Age).
- **Idle expiry is tested by moving timestamps in the database,** because Spring Session reads the system clock; absolute lifetime and the recent-authentication window use the application clock and are tested with a controllable one.
- **Login throttling.** Two counters (account and address pair, account alone) in `login_throttle`, escalating 1, 2, 4, 8, 15 minutes (cap); blocked attempts are refused without evaluating the password and are not counted, so they cannot extend a block. The 20-per-minute address limit also returns the ordinary login failure (AC-AUTH-18 and 26).
- **Residual risk for the security owner:** the account-wide ceiling (20 failures per hour) delays logins for that account from every address, up to 15 minutes at a time. As approved in D-51 it never locks the account, but an attacker who keeps failing from many addresses can keep the account delayed for as long as they keep trying. The pair limit and the ceiling are the approved design; consider notifying the owner or adding a trusted-device exception if this proves abusable.
- **Failure auditing differs by account existence** (D-88): an `auth.login.failed` row is written only for existing accounts, a small timing difference next to the 250 to 500 ms hash.
- **New error code `REAUTHENTICATION_REQUIRED` (403)** for sessions whose last authentication is stale; added to the contract.
- **Required new settings:** `SESSION_COOKIE_NAME` and `SESSION_COOKIE_SECURE` (defaults are the production values; local HTTP development overrides both, see `.env.example`).

## 12. Implementation notes (Phase 4, 2026-10-19)
- **No new table.** Reset tokens reuse `verification_token` with purpose `PASSWORD_RESET` (the Phase 2 migration already allowed it): hashed, single use, one hour. Issuing a new token invalidates the user's earlier reset tokens; confirming invalidates any others.
- **Request is deferred like resend.** Every accepted request queues the same outbox record for any well-formed address; the lookup, token minting, audit event, and email happen in the worker, so neither the response nor its timing depends on the account.
- **Order of checks at confirm:** malformed or unusable token (generic 422, cheap, no hashing) first, then the password policy (400, token left unused so the user can retry), then hashing, then one short transaction that consumes the token, stores the hash, invalidates other tokens, queues the notice, writes the audit events, and ends every session of the user as its last step.
- **Throttle behavior:** per account (3 an hour) is silent and neutral, per address (10 an hour) returns 429, and an unavailable counter store returns 503, as for resend.
- **Notice email** says the password changed and every device was signed out and links only to the forgot-password page; it carries no token.
- **Session revocation on reset** uses the same `SessionRevoker` that operators will use to disable an account.
- **Not changed:** the new password is not checked against the old one, and a reset does not clear login throttle state for the account (the user can reset, then wait out a block).

## 13. Implementation notes (Phase 5, 2026-10-19)
- **Design source.** The user supplied three mockups (kept in `docs/design/auth`) and asked for their layout, colors, fonts, and sizes. Tokens were sampled from the images; the heading serif is Newsreader 400 because its text widths match the mockups within 2 px (Fraunces and seven other serifs were 10 to 20 % wider); the text face is Inter. Mockup items that SPEC-AUTH does not cover were left out (D-92). The brand line "Leadership intelligence for teams that review how they lead." is neutral placeholder copy for the product owner to replace.
- **Pages:** `/signup`, `/check-email` (with a resend form), `/verify-email` and `/reset-password` (token read from the URL fragment, removed from the address bar at once, kept only in memory and sent only in a request body), `/login`, `/forgot-password`, and `/account` (profile: name, email, verification, session times, sign out). An ended session sends the user to `/login?notice=expired`; only fixed notice keys are honoured. There is no step-up page, because nothing in this slice needs it.
- **BFF** (`apps/web/src/lib/server/bff.ts`, route `/api/auth/[...path]`): nine allowed operations, request-header and cookie allowlists, exact Origin plus Sec-Fetch-Site plus JSON content type on POST, 8 KiB body limit, Set-Cookie and Retry-After passed back, `no-store`, generic 503 (D-93). No second session store.
- **Finding from the end-to-end tests:** a signed-in browser that opens a reset or verification link was rejected, because `GET /csrf` returns the session-bound token to callers with a session while those endpoints accept only the signed double-submit token. The BFF now has `csrf/pre-login`, which asks the API without the session cookie. The API and contract are unchanged. A later slice that adds pre-login endpoints must keep using it.
- **Second finding:** opening the same link again in the same tab changes only the URL hash and does not reload, so the page listens for `hashchange` and treats each opening as a new attempt.
- **Client types** are generated from `contracts/openapi.yaml` (`npm run gen:api`); CI regenerates them and fails on drift.
- **Not done in this phase:** a script-src Content-Security-Policy (needs per-request nonces; only frame-ancestors, base-uri, form-action, and object-src are set), a rate-limit-aware retry UI beyond the message, and the real client-address chain through a production edge (the BFF forwards `X-Forwarded-For` as received, so the edge must append the true client address and the API's trusted-proxy list must name the BFF). All three go to the Phase 6 review.
- **Known visual limits:** the mockups use 11 to 12 px text and a border color on inputs with a low contrast ratio; both were kept as asked, with 16 px inputs and 40 px controls below 640 px. Automated axe scans pass; no manual screen-reader or keyboard-only review was done yet.

## 14. Phase 6 report (2026-10-19)
### Measurements
Argon2id on a developer laptop (8 CPUs, Java 24; median per-hash latency at concurrency 1 / 8, hashes per second at 8):

| memory KiB | iterations | 1 thread | 8 threads | hashes/s at 8 |
|---|---|---|---|---|
| 19456 (current default) | 2 | 32 ms | 69 ms | 104 |
| 19456 | 3 | 78 ms | 97 ms | 77 |
| 46080 | 1 | 78 ms | 128 ms | 58 |
| 65536 | 2 | 203 ms | 293 ms | 27 |

The target of 250 to 500 ms per hash is not reached on this machine even at 64 MiB, so the laptop cannot set the final values. **Decision needed from the security owner:** the task size (vCPU and memory) and whether 250 ms is the floor. Then run `./mvnw test -Dtest=Argon2BenchmarkTest -Dbenchmark=true` on that size and choose the smallest parameters that reach the target. Until then the OWASP floor stays and D-12 stays provisional.

Rate-limit store: see traceability (Phase 6 results). The ADR-004 gate passes on a local database for correctness and throughput; it is not yet run on the production database class.

### Review findings and proposed handling
Both reviews were read-only and executed nothing. I have not verified every finding by running it; those marked (unverified) are code reading only.

| Ref | Finding | Type | Proposal |
|---|---|---|---|
| SEC-H1 / CR-P1 | BFF forwards the browser's `X-Forwarded-For` as received (ADR-001 says the BFF or edge overwrites). Spoofing defeats per-IP limits; a wrong trusted-proxy list puts all users in one bucket | High, needs deployment decision | Decide the edge (ADR-007), then make the BFF set the header from the platform's client address and refuse to start in production with the loopback-only proxy list. Add an end-to-end spoof test |
| SEC-M1 (unverified) | BFF-credential and pre-login CSRF filters match the raw URI; authorization matches the decoded path. A percent-encoded path may skip them | Medium, code fix | Use one matcher for all three and add an encoded-path test |
| SEC-M5 | `GlobalExceptionHandler` logs the full throwable, which can contain row data | Medium, code fix | Log class name and correlation id only |
| CR-M1 | A password-hash upgrade at login can overwrite a password reset that commits meanwhile | Medium, code fix | Make the upgrade conditional on the hash that was verified |
| SEC-M4 / CR-M2 | A login can create a session just after a reset revoked all sessions | Medium, design | Credentials-version in the session, checked by the session filter |
| CR-M3 / SEC-L4 | Login throttle is check-then-act; a burst gets more than 5 guesses (bounded by the per-IP limit). The concurrency test only asserts 401s | Medium to Low | Record the attempt atomically; strengthen the test |
| SEC-M2 / CR-S3 | The account-wide throttle lets an attacker keep a known address blocked | Product decision (D-51) | Re-confirm, or add a trusted-device exemption |
| SEC-M3 / CR-P3 | Pre-hijacking: an attacker registers a victim's address with their own password; the victim's click verifies it. Attacker-chosen display name appears in the email | Spec-level, product decision | Drop the display name from verification mail; consider claim-on-verify rules |
| CR-S1 | Per-email signup limit of 3 per 24 hours is not in the spec | Needs approval or removal | Already an open question |
| CR-S2 | Issuing a token invalidates earlier unused tokens, so a third party can keep burning a victim's reset link | Product decision | Decide whether to keep the old link valid until expiry |
| CR-M4 / SEC-L6 | `outbox_event` and `audit_event` have no retention; outbox payloads hold addresses that have no account | Medium to Low | Retention periods are a product and legal decision, then a purge job |
| CR-M5 | OpenAPI says password max 128 and display name max 100; DTOs allow 1024 and 400; documented 429s on token endpoints are never produced | Contract drift | Align the DTOs to the contract (the contract matches the spec) |
| CR tests | AC-AUTH-39 and the login fail-closed path are tested less deeply than the traceability implied | Test gap | Add a real-endpoint saturation test and a login fail-closed test |
| SEC-L1, L2 | Placeholder BFF credential passes length check; empty allowed-origins disables the Origin check | Low | Reject known placeholders; require origins in production |
| SEC-L7 | No script-src CSP, HSTS or Permissions-Policy | Low | Nonce CSP in Next.js middleware; HSTS at the edge |
| SEC-L8 | CI installs Redocly via `npx` without a lockfile; service images pinned by tag only; no dependency audit | Low | Pin by digest and add an audit step |

### Slice status
Slice 001 is **not closed**. Open: the items above, the edge-dependent checks, D-12 values, D-87, D-08, the `signup-account` limit question, and runbook rehearsals.

## 15. Phase 6 part 2: code fixes (2026-10-19)
Made, with tests (all in `./mvnw verify`, 239 tests, 0 failures):
- **SEC-M1:** the BFF-credential, pre-login CSRF and session filters now decide on the decoded path (`RequestPaths`, Spring's `UrlPathHelper`), not the raw URI. `EncodedPathTests` failed before the change (both requests got past the filters) and passes after.
- **SEC-M5:** the unhandled-exception handler logs the exception type and correlation id only, no throwable. Not covered by a new test.
- **CR-M1:** the hash upgrade at login is a conditional `UPDATE ... WHERE password_hash = <verified hash>`; `CredentialsVersionTests.aPasswordHashUpgradeNeverOverwritesAPasswordThatChangedMeanwhile`.
- **SEC-M4 / CR-M2:** migration `V8` adds `app_user.credentials_version`; a reset increments it, a session records the value it was opened with, and the session filter rejects a session whose value is stale. Existing sessions without the value are rejected (none exist yet). `CredentialsVersionTests` (two tests).
- **CR-M3 / SEC-L4:** attempts for one account from one address run one at a time on an instance (256 striped locks), so a burst is evaluated only up to the threshold. `LoginTests.aBurstOfGuessesIsStoppedAtTheThresholdNotAfterIt` asserts exactly 5 evaluated failures out of 12; with the lock removed it saw 12. **Residual:** across several API instances the check is still not atomic; the per-IP limit bounds it.
- **CR-M5:** removed the two `429` responses that no code path produces (verify-email, password-reset confirm) from the contract and regenerated the client types. The DTO size guards (1024 and 400 UTF-16 units) stay above the contract's code-point limits on purpose: the domain checks return the specific field error.

Not done (still open, see section 14): X-Forwarded-For chain, pre-hijacking and display name in mail, outbox and audit retention, token-invalidation and account-throttle product decisions, the AC-AUTH-39 and login fail-closed test gaps, placeholder-credential and empty-origins guards, CSP/HSTS, CI pinning and audit.

## 16. Phase 6 part 3: decisions D-95 to D-101 implemented (2026-10-19)
The user approved items 1 to 7 of the Phase 6 suggestions ("1 thru 7 approved"); they are recorded as D-95 to D-101 and as AC-AUTH-41 to AC-AUTH-44 in the spec. This branch is stacked on the part 2 branch (PR #19), which is not merged yet.
- **D-95 client address.** The BFF no longer forwards the browser's `X-Forwarded-For`. It sets one value from `CLIENT_IP_SOURCE`: `cloudfront` (viewer address) or `rightmost` (last entry, appended by the load balancer; also what the local and end-to-end setups use). Anything that is not an IP literal is dropped. The API refuses to start with the loopback-only `TRUSTED_PROXY_CIDRS` unless the profile is `local` or `test` (`TrustedProxyGuard`); the end-to-end API now runs with `SPRING_PROFILES_ACTIVE=local`. **Not verified:** the CloudFront and ALB configuration (prefix-list restriction, secret origin header) is infrastructure outside this repository.
- **D-96 Argon2.** Procedure only; the parameters stay at the provisional baseline until the benchmark runs on the production task size.
- **D-97 pre-hijacking.** `AccountRegistrar` replaces the password and display name of an unverified account on a new signup, bumps its credentials version, invalidates its verification tokens, and queues a new verification email; the response is unchanged. The verification email has no display name.
- **D-98 tokens.** Issuing keeps earlier unused tokens and retires the oldest beyond three live ones per user and purpose. Using one still retires the rest.
- **D-99 throttle.** Account-wide ceiling 100 per hour. A signed `__Host-device` cookie (HMAC over the account id and issue time, 90 days) is set at login; a valid one for the same account skips only the account-wide check. The BFF forwards that cookie. Step-up never uses the exemption.
- **D-100 retention.** Delivered outbox events are scrubbed when sent; `RetentionPurge` (every 6 hours) deletes sent events after 7 days, failed events after 30, and failed-login audit events after 365 days. **Deviation from the proposal:** the payload is scrubbed instead of storing a keyed hash, because the worker needs the address to find the account.
- **D-101 signup limit.** Documented in the spec and contract. The contract also had `429` on signup pointing at the wrong response and no `503`; both corrected.
- **Test gaps closed:** `HashSaturationTests` (signup, login, reset confirmation under real saturation), `FailClosedLoginThrottleTests` (login and step-up with the throttle table gone or failing), and a log-hygiene test for the exception handler.

Still not done: the guards for placeholder BFF credentials and empty allowed origins, CSP, HSTS, CI pinning and audit, runbook rehearsals, the Argon2 values, and the infrastructure behind D-95.

