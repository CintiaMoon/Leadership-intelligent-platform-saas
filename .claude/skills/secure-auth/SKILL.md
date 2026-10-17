# Secure Authentication Workflow

## When to use
Use for authentication, email verification, sessions, password handling, account recovery, invitation tokens, or security-sensitive identity changes.

## Procedure
1. Read the approved authentication spec, threat model, and ADR-001.
2. Confirm Spring Security owns the authoritative authentication and authorization decisions.
3. Verify server-side session, cookie, CSRF, session rotation, expiry, and logout semantics.
4. Store only hashes of random verification/invitation tokens; enforce expiry and single-use consumption atomically.
5. Apply approved rate limits and anti-enumeration behavior.
6. Test cross-tenant and privilege-boundary cases.
7. Ensure logs and telemetry redact secrets.
8. Ask for security review before weakening or bypassing any control.

## Completion checklist
- [ ] Public/protected endpoint matrix is documented.
- [ ] CSRF behavior is tested for cookie-authenticated mutations.
- [ ] Session expiry and revocation are tested.
- [ ] Token replay and concurrent use are tested.
- [ ] Account enumeration and lockout abuse are considered.
- [ ] No credentials or raw tokens appear in logs.
