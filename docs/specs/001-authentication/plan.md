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
