# ADR-004: Rate Limiting Store and Failure Behavior

- Status: Proposed — approved as a suggestion on 2026-10-16; reviewed by the security-engineer agent the same day (findings applied below). The login throttle key is decided (D-51)
- Date: 2026-10-16
- Decision owners: Security owner, Architect (roles, see the decision register)
- Related: decision register D-06, D-13; SPEC-AUTH, SPEC-INV, SPEC-TEN; ADR-001

## Context
SPEC-AUTH requires distributed limits by account, IP, and operation, shared across application instances (NFR-SEC-004). A BFF topology means per-IP limits depend on a trusted client-IP policy (ADR-001).

## Decision
- Counters are stored in PostgreSQL, so limits hold across instances without new infrastructure.
- If the store is unavailable, login, registration, verification-resend, password-reset, invitation-token, and company-creation endpoints fail closed (the request is rejected).
- Limits follow SPEC-AUTH, SPEC-INV, and SPEC-TEN: login 5 failed attempts per account and IP pair per 15 minutes with progressive delay, an account-only ceiling of 20 failed attempts per hour that adds a capped delay and never locks the account, and 20 per IP per minute; invitation emails 20 per inviter per hour, 50 per company per day, and 3 per recipient per 24 hours; registration 5 per IP per hour; verification resend 3 per account and 10 per IP per hour; invitation-token failures 10 per IP per hour; company creation 5 per IP per hour and 10 per account per day.

## Amendments (2026-10-19, D-78, D-86)
- Counters are fixed-window. The key is an HMAC-SHA-256 of the normalized identifier and the client IP, so raw emails and addresses are not stored. Updates use one atomic upsert with `RETURNING`.
- The counter pool is small and dedicated, with a 250 ms timeout; exceeding it means "store unavailable" and fails closed.
- Progressive delay is an escalating block window (1, 2, 4, 8, then 15 minutes), not a server-side sleep. While a key is blocked, the password is not evaluated and the generic login failure is returned.
- Neutral endpoints (verification resend, password-reset request) that are throttled by account still return the neutral response and send nothing; IP-only limits may return 429 with `Retry-After`.

## Alternatives considered
Redis, or an edge or WAF control. Both add infrastructure; an edge control may complement this later.

## Consequences
- A database outage also blocks these endpoints, which trades availability for abuse resistance.
- Counter writes add load to the database; the cost needs measuring before launch.
- Per-IP limits are only meaningful if the forwarded-header policy in ADR-001 is implemented.

## Implementation requirements (from the security review)
- Counter writes use a separate connection pool with a short timeout, so a flood cannot exhaust the pool used by business traffic.
- Counters are updated with cheap upserts that do not hold long locks, and an in-memory coarse limiter sits ahead of the database.
- A cleanup job removes expired counters.
- "Store unavailable" is defined as a timeout or connection failure of the counter write.
- The throttled response is identical for known and unknown accounts, so it is not an enumeration oracle.
- A load test of the counter store is a release gate.
- Per-IP limits depend on the forwarded-header policy in ADR-001.

## Verification
- Limits hold across two application instances.
- Endpoints reject requests when the store is unavailable.
- Per-IP limits use the trusted forwarded address only.
