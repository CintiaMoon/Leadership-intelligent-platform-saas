# Threat Model

## Status
Initial draft — update as architecture and deployment decisions are made.

## Assets
- User credentials and verified email state.
- Session identifiers and verification tokens.
- Invitation tokens and ownership-transfer state.
- Tenant-owned records.
- Plan and entitlement state.
- Audit records and deletion metadata.
- Secrets, database credentials, and email-provider credentials.

## Trust boundaries
- Browser/client to Next.js server.
- Next.js server to PostgreSQL.
- Server to email provider.
- Server to rate-limit store and job system.
- Application to logs, monitoring, and backups.
- One tenant's data boundary versus another tenant's data boundary.

## Threats and controls
| Threat | Minimum control |
|---|---|
| Credential stuffing | Shared rate limits, safe login behavior, monitoring, recovery policy |
| Account enumeration | Generic responses where appropriate, consistent behavior |
| Session theft | Secure cookies, server-side revocation, TLS, no token logging |
| CSRF | Verify framework protections and protect custom state-changing endpoints |
| Tenant ID manipulation | Central authorization and tenant-scoped data access |
| Privilege escalation | Server-side role checks and explicit permission matrix |
| Invitation replay | Hashed single-use token, expiry, atomic consumption |
| Ownership race | Transactional state transitions and concurrency tests |
| Last-Owner removal | Concurrency-safe invariant enforcement |
| Quota race | Atomic usage updates or appropriate locking |
| Purge race/replay | State checks, idempotency, durable jobs, audit trail |
| Secret leakage | Secret manager/environment configuration, log redaction, rotation |
| Email provider failure | Durable delivery, retries, safe idempotency |

## Required review scenarios
- Cross-tenant read and write attempts.
- Requests after membership revocation.
- Concurrent consumption of the same invitation or verification token.
- Concurrent Owner removal and ownership transfer.
- Expired or revoked sessions.
- Rate-limit store outage.
- Purge job retry and stale-job execution.
- Database restore and migration failure.

## Open decisions
- Abuse thresholds and lockout duration.
- Recovery and step-up authentication policy.
- Applicable privacy, legal, and retention obligations.
- Incident response and notification requirements.


## Spring Boot and browser/API boundary
- Spring Security is the backend authority for authentication and authorization. Do not maintain an independent Auth.js session as a second source of truth.
- Approve BFF versus direct API topology in ADR-001. Do not trust arbitrary forwarded identity headers.
- Cookie-authenticated state-changing requests require configured CSRF protection. CORS and SameSite are not substitutes for CSRF.
- Verify session rotation, expiry, logout invalidation, and revocation in tests.
- Use DTOs, safe error responses, and redacted structured logging.
- Every tenant-scoped operation verifies active membership and permission; tests must include cross-tenant attempts.
