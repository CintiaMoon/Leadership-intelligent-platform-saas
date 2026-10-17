# Spring Security Authentication and Authorization

## When to use
Use for login, logout, sessions, CSRF, endpoint access rules, account lockout, email verification, or privilege changes.

## Procedure
1. Read the authentication spec and approved session/API-boundary ADR.
2. Identify public, authenticated, verified-email-only, and role/tenant-protected endpoints.
3. Configure Spring Security explicitly; avoid broad permit-all rules or accidental default behavior.
4. Use server-side sessions per the approved design; test session fixation protection, rotation, expiry, logout invalidation, and revocation.
5. For cookie-authenticated state-changing requests, configure and test CSRF protection. SameSite is additional defense, not a substitute.
6. If cross-origin requests are required, use explicit CORS origins and credential policy; never use wildcard origins with credentials.
7. Use approved password hashing if passwords are in scope; never store plaintext or reversible passwords.
8. Store only hashes of verification/invitation tokens and consume tokens atomically.
9. Apply rate limits and account-abuse controls using shared state where required.
10. Write negative tests: unauthenticated, unverified, expired session, revoked session, missing/invalid CSRF, wrong tenant, wrong role, and brute-force limits.

## Checklist
- Backend authorization is not delegated to the UI.
- User/tenant identity comes from trusted security context and verified membership.
- Cookies use approved Secure/HttpOnly/SameSite/domain/path settings.
- Responses avoid account enumeration where required.
- Logs contain no credentials, cookies, or raw tokens.
