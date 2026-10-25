# Frontend slice F1 traceability

- Plan: `f1-plan.md` (approved 2026-10-25). Decisions: D-168 to D-175.
- Status by phase: F1-1 and F1-2 done (below). F1-3 to F1-5 not started.

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

## Phase F1-2: shell, My companies, create company, leave (P5, P6)
**What changed**
- Signed-in shell: `app/(app)/layout.tsx` with `AppHeader` (product name, My companies, the person's name linking to the profile, Sign out; D-169). `requireSession` (one session lookup per request) is used by the layout and by every page behind it; a 401 on a read goes to sign in. The profile page moved into the shell and lost its own header and Sign out button.
- `/companies`: server read of `listCompanies`, the list with role badges and Open links, the empty state of the brief, and the create form (name only; the role comes from the server).
- `/companies/[companyId]`: company name and role, "Company home is coming" (D-168) for every role, a notice for a company pending deletion, and Leave (P6). The id is checked as a UUID before any API call; a company the person cannot use, a missing one and a non-id all show the same "This page is not available." (D-175).
- Leave: a native modal dialog (`ConfirmDialog`) naming the company, with Cancel focused first, Escape to cancel, focus returned to the button; the Owner sees "Transfer ownership first." and no button.
- Landing: sign in, `/` and the accept page's links now go to My companies instead of the profile (approved 2026-10-25). The new pages are `no-store`.
- Error text for company actions: 403 and 404 give one "not available to you" message, 409 says it changed, 429 and 503 use the existing wait messages.

**Evidence**
| Claim | Test |
|---|---|
| List shows name, role and an Open link per company; the empty state; names are text, never markup | `companies.test.tsx` |
| Header has the product link, My companies, the profile link and Sign out | `companies.test.tsx` |
| Create sends only the name with the CSRF token, opens the new company; field error, 429 and 503 messages; 401 goes to sign in | `companies.test.tsx`; `e2e/companies.spec.ts` (bad name gets `aria-invalid`) |
| Owner has no leave button and sees the transfer note; leaving asks first, names the company, then sends one request; Cancel sends nothing; a refusal stays in the dialog | `companies.test.tsx`; mutation check (Owner guard removed) fails it |
| Real stack: sign in lands on My companies; create opens the company; list and profile navigation; sign out | `e2e/companies.spec.ts` |
| Real stack: a Viewer joined by invitation sees the role, opens the dialog (Cancel focused, Escape closes and returns focus), confirms and the company disappears | `e2e/companies.spec.ts` |
| Real stack: another person's company, a missing company and ids such as `not-a-uuid`, `a?b=c` and `../../api` show the same page; signed out goes to sign in | `e2e/companies.spec.ts`; mutation check (id check removed) fails it |
| No serious or critical axe violations: My companies (empty and with a company), the company page (Owner), the open dialog, the not-available page, the profile | `e2e/companies.spec.ts`, `e2e/auth.spec.ts` |
| Screenshots at 360, 768 and 1280 px reviewed (empty list, list, Owner page, Viewer page, leave dialog at 360); one fix came out of it: the role badge inside the heading inherited the heading font | `test-results/f1-2/*.png` (not committed) |

## Checks run (F1-2)
- Vitest 81 passed (8 files), `npm run typecheck`, `npm run lint`, `npm run build`: clean.
- Playwright 19 of 19 passed against the real API jar, the built web app, PostgreSQL and Mailpit (datasource override in `docs/runbooks/operations/invitations.md`).
- Mutation checks, both caught and restored: Owner guard on Leave; UUID check on the company page.
- Not run: `./mvnw verify` (no backend change; once at the end of F1), contract lint and type-drift check (contract unchanged), the production-cookie E2E variant, other browsers, CI, keyboard-only and screen-reader passes beyond axe and the dialog focus checks (planned for F1-5).
