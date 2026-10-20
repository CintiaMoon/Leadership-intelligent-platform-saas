# Signup and verification email: sequence of steps

- Applies to: SPEC-AUTH (slice 001), `POST /api/auth/signup` through to `POST /api/auth/verify-email`
- Source: read from the code on 2026-10-20. Related: ADR-001 (BFF), ADR-003 (outbox), ADR-004 (rate limits), D-75, D-79, D-81, D-97, D-98.

## Why the email is not sent during the request
Principle 10 in `CLAUDE.md`: no network call and no email inside a database transaction. The signup request only writes the account and an **outbox** row in one transaction. A background worker sends the email later. The browser gets the same neutral `202` whether the address is new or already registered (AC-AUTH-02), so the response cannot be used to find out who has an account.

## Sequence

```mermaid
sequenceDiagram
    autonumber
    actor U as User (browser)
    participant W as Next.js BFF
    participant A as Spring Boot API
    participant DB as PostgreSQL
    participant K as Outbox worker (in the API)
    participant S as SMTP server (Mailpit locally)
    actor M as User's mailbox

    Note over U,W: Step A: the signup request
    U->>W: GET /api/auth/csrf/pre-login
    W->>A: GET /api/v1/auth/csrf (X-BFF-Credential, no session cookie)
    A-->>W: signed double-submit CSRF token + cookie
    W-->>U: token
    U->>W: POST /api/auth/signup {email, password, displayName} + X-CSRF-TOKEN
    W->>W: check Origin, Sec-Fetch-Site, JSON content type, body size
    W->>A: POST /api/v1/auth/signup (adds X-BFF-Credential, one X-Forwarded-For)
    A->>A: BffTrustFilter, CSRF check
    A->>DB: rate limit: 5 per IP per hour (fails closed with 503 if the store is down)
    A->>A: validate email, display name, password policy (400 with field errors)
    A->>A: hash password with Argon2id (done for new and existing addresses alike)
    A->>DB: rate limit: 3 per normalized email per 24 hours (over the limit: silent, same 202)

    Note over A,DB: Step B: one transaction (AccountRegistrar.register)
    A->>DB: find account by normalized email
    alt address is new
        A->>DB: INSERT app_user (email_verified = false)
        A->>DB: INSERT outbox_event AUTH_VERIFICATION_EMAIL {userId}
        A->>DB: INSERT audit_event auth.user.registered
    else account exists and was never verified
        A->>DB: replace password and display name, invalidate old verification tokens (D-97)
        A->>DB: INSERT outbox_event AUTH_VERIFICATION_EMAIL {userId}
        A->>DB: INSERT audit_event auth.user.registration_replaced
    else account is verified (or disabled)
        A->>DB: INSERT outbox_event AUTH_ACCOUNT_EXISTS_NOTICE {userId}
    end
    A-->>W: 202 {message: fixed text}
    W-->>U: 202, "check your email"

    Note over K,M: Step C: delivery, after the response (every 2 s)
    loop OutboxScheduler poll
        K->>DB: claim up to 10 due events (5-minute lease)
        K->>DB: open transaction: find user, still unverified and ACTIVE?
        K->>K: new token = 256 random bits, base64url (TokenCodec)
        K->>DB: INSERT verification_token (SHA-256 of token only, expires in 24 h, max 3 live per user, D-98)
        K->>K: build email, link = PUBLIC_BASE_URL/verify-email#token=...
        K->>S: SMTP send (outside any database transaction)
        S->>M: deliver email
        K->>DB: mark event SENT and scrub its payload (D-100)
    end
    Note over K,DB: On failure: markFailed, retry with backoff 30 s doubling up to 1 h, then FAILED for an operator

    Note over U,A: Step D: verification
    M->>U: user opens the link
    U->>W: GET /verify-email#token=... (the fragment is never sent to a server)
    W-->>U: page; script reads the token, removes it from the address bar
    U->>W: POST /api/auth/verify-email {token} + X-CSRF-TOKEN
    W->>A: POST /api/v1/auth/verify-email
    A->>DB: UPDATE verification_token SET consumed WHERE hash matches AND unused AND not expired (single use)
    A->>DB: UPDATE app_user SET email_verified = true
    A-->>W: 204 (an unknown, expired or used token gives the same 422 TOKEN_INVALID)
    W-->>U: "email verified", user can log in
```

## What the diagram leaves out on purpose
- The verification email carries no display name (D-97). The raw token exists only in the email and in the browser's memory; the database holds only its SHA-256 hash.
- If the address belongs to a verified account, the worker sends the owner an "attempted registration" notice instead, with no link to act on. The caller sees no difference.
- Resend (`POST /api/auth/verify-email/resend`) follows steps B and C with the event type `AUTH_RESEND_VERIFICATION`, with its own rate limits (AC-AUTH-18); the oldest live token is retired when a 4th is issued (D-98).
- No user is logged in by signup or verification. Login is a separate flow.

## Where to look in the code
| Step | Code |
|---|---|
| BFF checks | `apps/web/src/lib/server/bff.ts` |
| Request handling, limits, validation | `SignupService`, `AuthController` |
| Transaction and outbox row | `AccountRegistrar`, `OutboxRepository.enqueue` |
| Polling and delivery | `OutboxScheduler`, `OutboxWorker`, `AuthOutboxHandlers.VerificationEmailHandler` |
| Token and email text | `VerificationEmails`, `TokenCodec`, `AuthEmails` |
| SMTP | `SmtpEmailSender` (Spring `JavaMailSender`) |
| Link handling in the browser | `apps/web/src/components/auth/useFragmentToken.ts` |

Locally, open the sent email in Mailpit at `http://localhost:8025`.
