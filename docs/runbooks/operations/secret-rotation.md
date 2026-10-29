# Runbook: Secret rotation

- Status: Draft (Phase 6, 2026-10-19). Written from the code; **not rehearsed** in a deployed environment. Rehearse once before relying on it.
- Owner role: DevOps lead
- Related: ADR-001, D-50

## BFF-to-API credential (90 days, or at once if exposed)
The API accepts up to the configured number of credentials in `BFF_CREDENTIALS` (comma separated, each at least 43 characters), compared in constant time. This allows rotation without downtime.

1. Generate: `openssl rand -base64 48 | tr '+/' '-_' | tr -d '='`. Store it in the secret manager only.
2. API: set `BFF_CREDENTIALS=<old>,<new>` and deploy. Both work now.
3. Web: set `BFF_CREDENTIAL=<new>` and deploy.
4. Check: log in through the web app and confirm the API returns no 401 for the web tasks' requests.
5. API: set `BFF_CREDENTIALS=<new>` and deploy.
6. Verify the old value is dead: `curl -i -H "X-BFF-Credential: <old>" https://<api-host>/api/v1/auth/csrf` must not return 200.

Emergency (credential leaked): do steps 2 to 5 without waiting, then review `audit_event` and access logs for the exposure window.

## CSRF signing key (`CSRF_HMAC_KEY`) and rate-limit key (`RATE_LIMIT_HMAC_KEY`)
Each is a single value, with no overlap mechanism. Changing it has these effects:
- CSRF key: every pre-login CSRF token issued before the change is rejected. The web app fetches a fresh token for each submit, so users see at most one "request could not be verified" message. Safe at any time.
- Rate-limit key: until PR "remove the committed rate-limit key" the repository shipped a default value for it (still in git history). Any environment that started without `RATE_LIMIT_HMAC_KEY` used that public value: set a generated one (`openssl rand -base64 48`). The API now refuses to start without the key and refuses that value. All counters restart from zero (identifiers are stored as keyed hashes), which briefly removes lockout state. Rotate at a quiet time and not during an attack.

## Database and SMTP/SES credentials
Not yet covered: SES wiring waits for D-08. Add the procedure when the production database role model is set up.

## Test record
None yet. Record the date, the tester, and the result after the first rehearsal.
