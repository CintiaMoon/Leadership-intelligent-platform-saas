# Frontend slice F1 traceability

- Plan: `f1-plan.md` (approved 2026-10-25). Decisions: D-168 to D-175.
- Status by phase: F1-1 done (below). F1-2 to F1-5 not started.

## Phase F1-1: BFF operations and browser client (no UI)
**What changed**
- `lib/server/bff.ts`: `POST /api/auth/step-up`; a path-pattern allowlist for the nine company operations (`companyTarget`: method plus shape, `{id}` is exactly one canonical UUID, ids sent in lower case, no query string forwarded); `forward` takes an operation family; every method except GET gets the origin and `Sec-Fetch-Site` checks; POST and PATCH need a JSON content type and respect the 8 KB limit; DELETE sends no body.
- `app/api/companies/[[...path]]/route.ts`: POST, PATCH and DELETE only (no GET: reads are made on the server, D-173).
- `lib/api/client.ts`: `send(method, url, body)` for POST, PATCH and DELETE with the session-bound CSRF token; `post` unchanged for authentication calls.
- `lib/server/api.ts`: `readApi` for server-rendered pages (plain `/api/v1/` paths only, the caller's session cookie, status only on failure).
- Existing tests that used `step-up` as their example of an unlisted operation now use a different unlisted one.

**Evidence**
| Claim | Test |
|---|---|
| Each of the ten operations reaches exactly its API path with the session cookie, the CSRF header and the BFF credential, and nothing else | `bff.test.ts` (nine parameterized company operations, the step-up test) |
| Near misses are a 404 and never reach the API (wrong or extra segments, non-UUIDs, short or long ids, encoded slash and dot-dot, empty segment, wrong method, wrong case, GET) | `bff.test.ts` "near misses are a 404" (21 cases) |
| PATCH and DELETE from another origin, marked cross-site or same-site, or with no origin are 403 and never reach the API; a PATCH without a JSON content type is 415; an over-size PATCH is 413 | `bff.test.ts` |
| A DELETE sends no body and no query string even if the browser attached them | `bff.test.ts` |
| Problem bodies, Retry-After and no-store pass back; internal headers do not | `bff.test.ts` |
| The browser client sends the right method, headers and body, returns the answer, and reports an unavailable BFF without sending | `client.test.ts` |
| Server reads send only the allowed cookie and credential, keep only a status on failure and refuse non-API paths | `api.test.ts` |
| Real stack: create company, create, resend and revoke an invitation (a second revoke is 409), 404s from the API for unknown member ids, the Owner cannot leave, step-up wrong password 401 and right password 204, near misses 404, other origins 403, no session refused | `e2e/bff-companies.spec.ts` (2 tests) |

**Mutation checks (both caught, files restored):** a permissive id check; origin checks applied to POST only.

## Checks run (F1-1)
- Vitest: 69 passed (7 files). `npm run typecheck`, `npm run lint`, `npm run build`: clean.
- Playwright: 15 of 15 passed against the real API jar, the built web app, PostgreSQL and Mailpit (with the datasource override in `docs/runbooks/operations/invitations.md`).
- Not run: `./mvnw verify` (no backend change in this phase; planned once at the end of F1), the contract lint and type-drift check (the contract did not change), the production-cookie variant of the end-to-end suite, other browsers, CI.
