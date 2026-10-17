# Authentication Specification

- ID: SPEC-AUTH
- Status: Approved
- Re-approved on 2026-10-17. Approver: product owner.
- Requirements: AUTH-001 through AUTH-010

## Objective
Provide secure signup, email verification, login/logout, revocable server-side sessions, CSRF protection, rate limiting, and account-abuse controls.

## Proposed implementation constraints
- Spring Boot API using Spring Security.
- Spring Session JDBC is the proposed server-side session store, subject to ADR-001 approval.
- PostgreSQL with Flyway migrations; never use production schema auto-update.
- Next.js presents the UI. The approved BFF/direct API decision defines how cookies and CSRF tokens flow.
- Email delivery is behind a provider adapter and uses durable/retry-safe delivery where required.

## Authentication Decisions
- Authentication methods: Email and password only. External identity providers and enterprise SSO are deferred.
- Password policy: Minimum 8 characters, maximum at least 64 characters, all character types permitted, common/compromised-password blocklist, no arbitrary composition rules or mandatory periodic changes.
- Password hashing: Argon2id, using OWASP-recommended baseline parameters or stronger parameters established through deployment benchmarking.
- Rate limiting: Distributed, layered limits by account identifier, IP, and operation. Initial defaults: login 5 failed attempts per account and IP pair per 15 minutes, then progressive delay (an account-only ceiling of 20 failed attempts per hour adds a capped delay and never locks the account; decision D-51); 20 login attempts per IP per minute; registration 5 attempts per IP per hour; verification resend 3 per account per hour and 10 per IP per hour.
- Lockout: Progressive delays and temporary throttling. No indefinite account lockout based solely on failed login attempts.
- Password recovery: Excluded from V1 only if the product owner explicitly accepts the usability and support risks and approves an audited manual recovery process. Reconsider before public launch.
- Recent authentication: Authentication within the previous 10 minutes is required for email/password changes, ownership transfer, company deletion, and other designated sensitive operations. The last-authentication time is set only by a login or a password step-up, never by session activity or renewal, and it is evaluated server-side at the state-changing request; a stale value is rejected, not queued. No password-change or email-change flow exists in V1; if one is added, the user's other sessions are revoked on change.
- Sessions: Server-side sessions. Idle timeout 30 minutes; absolute lifetime 12 hours. Rotate session identifiers after authentication and privilege changes. Revoke server-side sessions on logout and applicable security events.
- Browser/API architecture: Next.js BFF for browser access. Spring Boot remains authoritative for authentication-related policy, business authorization, and tenant isolation.
- Email verification: Single-use secure token, 24-hour expiry, throttled resend, neutral duplicate-account responses, and server-side enforcement of verification status.
- Unverified accounts: Login is rejected until the email is verified, with a response indistinguishable from a wrong-password failure. Verification resend is requested by email address without authentication, is throttled, and returns the same neutral response whether or not an unverified account exists (D-16, decided 2026-10-16).
- Rate-limit store: Counters are kept in PostgreSQL so limits are shared across instances. If the store is unavailable, login, registration, verification resend, and invitation-token endpoints fail closed (D-06, decided 2026-10-16; ADR-004).
- Security invariants: No cross-tenant data access; no client-only authorization; no raw password or verification-token logging; no permanent lockout caused solely by attacker-controlled failed attempts.
- Validation: Security tests must cover rate limiting, enumeration, token replay and expiry, session expiry and revocation, CSRF, privilege changes, and tenant isolation before release.


## Functional flows
1. Validate and normalize signup input at the API boundary.
2. Create an account in the required unverified state.
3. Generate a cryptographically random verification token; persist only its hash, purpose, expiry, and consumption state.
4. Enqueue/deliver verification through a retry-safe mechanism without logging the raw token.
5. On verification, validate expiry, purpose, account state, and single-use semantics atomically.
6. Reject login for accounts whose email is not verified.
7. Establish a server-side session and rotate the session identifier at authentication boundaries as appropriate.
8. On logout, invalidate server-side session state and clear the browser cookie.
9. Support approved session expiry and revocation behavior.

## Security requirements
- Passwords use an approved adaptive password-hashing algorithm and configuration; never encrypt passwords reversibly.
- Tokens are generated securely, stored hashed, time-limited, and single-use.
- Cookie configuration is Secure in production, HttpOnly, scoped appropriately, and uses an approved SameSite policy.
- Spring Security CSRF behavior is explicitly configured and tested for all cookie-authenticated state-changing endpoints.
- Rate limiting is shared across application instances or uses an approved edge control.
- Lockout cannot be used as an easy permanent denial-of-service mechanism.
- Error responses do not unnecessarily disclose account existence.
- Session fixation protection, logout invalidation, and session revocation are tested.
- CORS, if required, uses explicit origins; CORS is not a CSRF defense.
- Never trust client-supplied identity, roles, or verification state.
- Credentials, cookies, raw verification tokens, and raw session identifiers are absent from logs.

## Illustrative API operations
Exact paths, DTOs, response codes, and CSRF headers must be approved in OpenAPI. Candidate operations include signup, verify-email, login, logout, and current-session lookup. Do not expose session secrets in response bodies or URLs.

## Acceptance criteria
- AC-AUTH-01: Signup validates input on the server.
- AC-AUTH-02: Signing up with an email whose normalized form already exists returns the same neutral response as a new signup and does not reveal that the account exists.
- AC-AUTH-03: Verification token expires and cannot be consumed twice, including concurrent consumption attempts.
- AC-AUTH-04: An unverified user cannot perform protected verified-only actions.
- AC-AUTH-05: Successful login establishes a server-side session with a newly rotated session identifier, a 30-minute idle timeout, and a 12-hour absolute lifetime.
- AC-AUTH-06: Logout revokes server-side session state and clears the cookie.
- AC-AUTH-07: Revoked or expired sessions cannot authorize protected requests.
- AC-AUTH-08: Rate limits and lockout rules are enforced across instances.
- AC-AUTH-09: Applicable CSRF-invalid requests are rejected.
- AC-AUTH-10: Credentials and raw tokens are absent from logs.
- AC-AUTH-11: Email addresses that differ only by letter case resolve to one normalized address, and the same normalization is applied at signup, verification, login, and invitation matching.
- AC-AUTH-12: Presenting an already-consumed verification token again is rejected and does not change account state.
- AC-AUTH-13: Login failures for an unknown account and for a wrong password return indistinguishable responses (status, body shape, and error code).
- AC-AUTH-14: Ownership transfer initiation, ownership transfer acceptance, and company deletion requests are rejected unless the session's last authentication was within the previous 10 minutes.
- AC-AUTH-15: A session idle for more than 30 minutes cannot authorize requests.
- AC-AUTH-16: A session older than 12 hours cannot authorize requests, regardless of activity.
- AC-AUTH-17: Passwords shorter than 8 characters or found on the compromised-password blocklist are rejected; passwords up to at least 64 characters, with any character types, are accepted.
- AC-AUTH-18: With the initial default limits, each of these is throttled: the 6th failed login for one account from one IP address within 15 minutes; the 21st login attempt from one IP within one minute; the 6th registration attempt from one IP within an hour; the 4th verification resend for one account within an hour; and the 11th from one IP within an hour.
- AC-AUTH-19: Login for an account whose email is unverified is rejected with a response indistinguishable from a wrong-password failure.
- AC-AUTH-20: A verification-resend request returns the same neutral response whether or not the email belongs to an unverified account.
- AC-AUTH-21: When the rate-limit store is unavailable, login, registration, verification-resend, and invitation-token requests are rejected.
- AC-AUTH-22: Session activity or renewal does not refresh the last-authentication time; only a login or step-up does.
- AC-AUTH-23: A user's session identifier is rotated after a change to their own role or membership.
- AC-AUTH-24: Authentication responses carry `Cache-Control: no-store`.
- AC-AUTH-25: More than 20 failed logins for one account within an hour add a capped delay but never lock the account.
- AC-AUTH-26: A throttled login response is indistinguishable from a wrong-password failure.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| AUTH-001 | AC-AUTH-01, AC-AUTH-17 |
| AUTH-002 | AC-AUTH-11 |
| AUTH-003 | AC-AUTH-03, AC-AUTH-12 |
| AUTH-004 | AC-AUTH-04, AC-AUTH-19 |
| AUTH-005 | AC-AUTH-05, AC-AUTH-06 |
| AUTH-006 | AC-AUTH-05, AC-AUTH-07 |
| AUTH-007 | AC-AUTH-07, AC-AUTH-15, AC-AUTH-16, AC-AUTH-23 |
| AUTH-008 | AC-AUTH-08, AC-AUTH-18, AC-AUTH-21, AC-AUTH-25 |
| AUTH-009 | AC-AUTH-08 (lockout only). Recovery exclusion is conditional on explicit product-owner acceptance and an audited manual process (not yet recorded), so no recovery criterion |
| AUTH-010 | AC-AUTH-02, AC-AUTH-13, AC-AUTH-20, AC-AUTH-26 |
| NFR-SEC-003 (supporting) | AC-AUTH-09, AC-AUTH-10 |
| Recent authentication (supporting) | AC-AUTH-14, AC-AUTH-22 |
| Response caching (supporting) | AC-AUTH-24 |

## Tests
Unit tests for validation/token utilities; PostgreSQL/Testcontainers integration tests for token consumption and persistence; Spring Security tests for CSRF, session lifecycle, authorization, rate limits, lockout, and enumeration; E2E tests for signup-to-verified-login through the approved browser/API topology.
