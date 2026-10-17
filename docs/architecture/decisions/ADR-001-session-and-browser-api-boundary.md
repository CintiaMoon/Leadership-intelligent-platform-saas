# ADR-001: Session Authority and Browser-to-API Boundary

- Status: Proposed — Option A (Next.js BFF) is selected in SPEC-AUTH; the proposals below were approved as suggestions on 2026-10-16 and reviewed by the security-engineer agent the same day (findings applied). The deployment topology (D-08) is still open, so this ADR is not yet accepted
- Date: TBD
- Decision owners: TBD
- Related: decision register D-01, D-02, D-06; SPEC-AUTH; SPEC-MEM

## Context
The product requires revocable server-side sessions, CSRF protection, and a Next.js frontend with a Spring Boot API. Two independently authoritative session systems would create inconsistent identity and revocation behavior.

## Decision
SPEC-AUTH (Approved) selects **Option A — Next.js BFF** for browser access. Spring Boot remains authoritative for authentication-related policy, business authorization, and tenant isolation.

Session parameters stated in SPEC-AUTH:
- Server-side sessions; Spring Session JDBC is the proposed store.
- Idle timeout 30 minutes; absolute lifetime 12 hours.
- Session identifiers are rotated after authentication and privilege changes.
- Server-side sessions are revoked on logout and applicable security events.

Session revocation stated in SPEC-MEM: every protected tenant request verifies that the user still has an active membership and sufficient permission, so membership removal and role changes take effect on the next protected request even while the session remains valid.

### Option A — Next.js BFF (selected)
- Browser calls same-origin Next.js Route Handlers.
- Next.js forwards the authenticated session and required CSRF context to Spring Boot using a documented, allowlisted proxy implementation.
- Spring Boot remains the final authority for authentication, authorization, tenant isolation, and business rules.
- Proxy headers and internal service trust are constrained; arbitrary client-supplied identity headers are never trusted.

### Option B — Direct browser-to-Spring API (not selected)
- Browser calls Spring Boot directly, ideally through same-origin ingress/reverse-proxy routing.
- If cross-origin, configure explicit allowed origins, methods, headers, and credential behavior.
- Spring Security owns the session and CSRF token lifecycle.
- CORS is not a substitute for CSRF protection.

## Proposed policies (approved as suggestions 2026-10-16; pending security review)
- Cookie: name with the `__Host-` prefix (which requires Secure, Path=/, and no Domain), HttpOnly, Secure, SameSite=Lax. Because `__Host-` requires Secure, local HTTP development uses a documented development-only profile that is excluded from production configuration. No state change is reachable by GET. The BFF also checks the `Origin` header on state-changing routes.
- Authentication responses carry `Cache-Control: no-store`.
- CSRF: Spring Security CSRF protection stays enabled. The BFF obtains the CSRF token from Spring Boot and sends it as a request header on every state-changing call. The CSRF token is bound to the session, issued at login, and re-issued whenever the session identifier rotates. CORS is not relied on for CSRF defense.
- BFF-to-backend trust: the BFF reaches Spring Boot over a private network and presents a per-environment shared secret of at least 256 bits, held in the secret manager, rotated at least every 90 days and on suspected leak, with two secrets valid during rotation. It authenticates the BFF as a client only; user identity comes from the session. mTLS replaces it if the platform chosen under D-08 supports it (D-50, decided 2026-10-16). Spring Boot accepts requests only from the BFF and ignores forwarded headers on requests that lack the credential. The BFF only forwards an allowlist of headers.
- Forwarded headers: the BFF or ingress overwrites `X-Forwarded-For` with the socket address of the client connection and never appends to a client-supplied value. Spring Boot processes forwarded headers only from known proxy addresses (a configured CIDR allowlist) and takes the rightmost untrusted hop as the client address. Per-IP rate limits use that address.
- Session store: Spring Session JDBC with framework-managed tables. The browser cookie carries the Spring session identifier; the BFF passes it through unchanged and holds no separate session state.

## Consequences
- Auth.js must not maintain a separate authoritative application session unless a future ADR defines a deliberate federation/bridge design.
- The BFF is a proxy, not an authority: it must not bypass or duplicate backend authorization, and its own routes must not expose data without the backend's checks.
- SPEC-AUTH applies per-IP rate limits. Behind a BFF, Spring Boot sees the BFF's address unless the trusted forwarded-IP policy above is implemented and tested.
- Frontend and backend tests must cover login, logout, expiry, revocation, CSRF, and unauthorized API access.
- Deployment topology and cookie behavior must be tested in an environment matching production routing.

## Open items
- Deployment topology: where the BFF and API run and how they are routed (D-08). ADR-007 proposes an AWS baseline for this.
- Date and decision owners.

## Verification
- Security tests for login, logout, session expiry (30 minutes idle, 12 hours absolute), revocation, session fixation, and CSRF-invalid requests.
- A test showing that a request made through the BFF with a revoked membership is denied by Spring Boot.
- A test showing that client-supplied identity headers are ignored.
- A test showing that per-IP rate limits use the forwarded client address only when it comes from the trusted proxy, and that a client-supplied `X-Forwarded-For` does not change the address used for rate limiting. These tests are release gates.
- Cookie behavior verified in an environment matching production routing.

## Approval checklist
- [x] BFF or direct API selected (Option A, per SPEC-AUTH)
- [x] Cookie domain/path/SameSite/Secure/HttpOnly policy documented (proposed above)
- [ ] CSRF token flow documented and tested (documented above; not yet tested)
- [x] Session idle and absolute expiry approved (30 minutes idle, 12 hours absolute, per SPEC-AUTH)
- [x] Logout and revocation semantics approved (SPEC-AUTH logout and security-event revocation; SPEC-MEM next-request membership revocation)
- [x] Trusted proxy and forwarded-header policy documented (proposed above)
- [x] BFF-to-backend trust model documented (proposed above)
- [x] Independent security review completed (security-engineer agent, 2026-10-16; findings applied)
