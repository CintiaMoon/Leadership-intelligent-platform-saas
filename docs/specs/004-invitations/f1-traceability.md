# Frontend slice F1 traceability

- Plan: `f1-plan.md` (approved 2026-10-25). Decisions: D-168 to D-175.
- Status by phase: F1-1 to F1-4 done (below). F1-5 (review and closure) not started.

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

## Phase F1-3: Members tab and the password dialog (P2 members, P4)
**What changed**
- `/companies/[companyId]/members`: server reads the company and the member list; Owners and Admins only (Agents, Viewers and anybody else get the same "not available" page). Each member shows name, address, role, status, join date and "(you)" for the caller. Dates are formatted on the server so both sides print the same text.
- Actions come from `lib/memberActions.ts`, which mirrors the matrix and `MembershipChanges`: nobody gets controls on themselves or the Owner; an Admin only acts on Agents and Viewers and can only assign those two roles; a suspended member can only be reactivated or removed; a company that is not ACTIVE is read-only. The API still decides every request.
- Change role (a select, sent at once), suspend and remove (each asks first, naming the person; remove says "They will need a new invitation to rejoin."), reactivate (at once). After every answer the list is read again. A 409 says the member changed in the meantime; a 403 or 404 says it is not available any more; 429 and 503 use the wait messages; a 401 goes to sign in.
- Step-up (D-174): a 403 `REAUTHENTICATION_REQUIRED` opens "Confirm your password"; on success the same request is sent once more, on Cancel it is dropped, a wrong password shows the sign-in message and keeps the dialog, and a second refusal is never prompted again. The password is sent only to the step-up operation and the field is cleared on every close.
- Company page: Owners and Admins are redirected to Members (D-168); Agents and Viewers still see the placeholder with Leave. `CompanyHeader` renders the name, the role and the tab row (Members only; none for Agents and Viewers). Leave is on the Members page (the Admin's button, the Owner's note).
- Deviation from the brief: a role select and plain buttons instead of a "row menu", which avoids building an ARIA menu; same actions, same confirmations.
- E2E helpers: `apiLogin` and `makeTeam` build an Owner and members with distinct names through real invitations (API calls, not the BFF).

**Evidence**
| Claim | Test |
|---|---|
| Who gets which controls (Owner, Admin, suspended, self, Owner row, removed, Agent, Viewer, unknown role, pending deletion) | `memberActions.test.ts`; mutation check (Admin restriction removed) fails it and a rendering test |
| Rows show the details and `(you)`; controls match the caller; text only, never markup; tabs only for those who manage members | `members.test.tsx` |
| Change role, suspend, remove, reactivate send exactly one request with the right method, path and body; confirmations name the person; Cancel sends nothing | `members.test.tsx` |
| 409, 403/404, 429/503 and 401 handling | `members.test.tsx` |
| Step-up: the request order is request, step-up, same request; wrong password keeps the dialog and does not retry; Cancel drops the action and clears the field; no second prompt; the password appears in no other request | `members.test.tsx`; mutation check (second prompt allowed) fails it |
| Real stack: an Owner changes a role, suspends (Escape cancels first, Cancel focused), reactivates and removes; counts and statuses update; own row has no controls | `e2e/members.spec.ts` |
| Real stack: an Admin has no controls on the Owner or another Admin and only Agent and Viewer to assign, can leave and then the company is "not available"; an Agent sees the placeholder, no tabs, and Members is "not available" | `e2e/members.spec.ts` |
| Real stack: a member removed in another window is explained when the stale page acts, and the list refreshes | `e2e/members.spec.ts` |
| Axe: Members page (Owner, Admin) and the open confirmation, no serious or critical violations | `e2e/members.spec.ts` |
| Screenshots at 360, 768 and 1280 px reviewed (Owner, suspended, Admin); one fix: the role badge was shown next to a select with the same value, so it now shows only on rows without a select | `test-results/f1-3/*.png` (not committed) |

## Checks run (F1-3)
- Vitest 105 passed (10 files), `npm run typecheck`, `npm run lint`, `npm run build`: clean.
- Playwright 22 of 22 passed against the real API jar, the built web app, PostgreSQL and Mailpit, on servers started by Playwright (datasource override in `docs/runbooks/operations/invitations.md`).
- Mutation checks, both caught and restored: Admin restriction in `memberActions`; the guard against a second password prompt.
- **One failure I could not explain:** the first run of the new members spec showed Next's "This page couldn't load" on the Members page in one test (and ran into the 60 s limit in the others). It did not reproduce in four later runs of the same flows, including against servers I started by hand and watched, and I did not capture the server log of the failing run. The test timeout was raised to 180 s because building a team takes about 20 s. Treat it as an unexplained flake to watch in CI.
- Not run: the stale-login step-up against a real session (a session cannot be made 10 minutes old inside a test; the unit tests cover the sequence and `e2e/bff-companies.spec.ts` covers the step-up operation itself), `./mvnw verify` (no backend change; once at the end of F1), contract lint and type-drift check (contract unchanged), the production-cookie E2E variant, other browsers, CI.

## Phase F1-4: pending invitations tab and the invite dialog (P2 pending, P3)
**What changed**
- `/companies/[companyId]/invitations`: server reads the company and its pending invitations; Owners and Admins only (everyone else gets the "not available" page). Rows show address, role, expiry and "Sending" until a link has been issued. The list never has a token or a link, because the API does not return one. The tab row now has Members and Pending invitations.
- Resend (at once) and Revoke (asks first, names the address). What is offered comes from `lib/invitationActions.ts`, which mirrors `InvitationRules`: an Owner may use every invitation, an Admin only those for Agents and Viewers, nobody in a company that is not ACTIVE. The API decides every request.
- "Invite someone" (on Members and on Pending invitations): one address and one role the caller may give (Owner: Viewer, Agent, Admin; Admin: Viewer, Agent; the safest role is the default). An invalid address is a field error; a member or an existing invitation is one message (the API uses one code for both, on purpose); 429 says to wait; inviting an Admin with a stale login opens the password dialog over the form, keeps what was typed and sends once more. The capacity warning of the brief is not built: it needs the plan data of slice 006 (AC-INV-26).
- `useActionRunner` now holds the shared handling of answers (success, 401, step-up retry, conflict, refusal, waiting messages); the Members list was moved onto it with its 18 tests unchanged.

**Evidence**
| Claim | Test |
|---|---|
| Owner and Admin may invite the roles above, nobody else; resend and revoke follow the same limit; a company that is not ACTIVE offers nothing | `invitationActions.test.ts`, `invitations.test.tsx`; mutation check (everything allowed) fails both |
| Rows show address, role, expiry, "Sending" only until sent; the empty state; no token or link in the page; text only | `invitations.test.tsx` |
| Resend sends one POST; revoke asks first, names the address, then sends one DELETE; Cancel sends nothing; 409 is explained and refreshes | `invitations.test.tsx` |
| Resending an Admin invitation with a stale login: request, step-up, same request | `invitations.test.tsx` |
| Invite dialog: roles by caller; trimmed address and role posted; closes and names the address with a link to the pending list (no link on that page); field error keeps the text; 409 and 429 messages; Admin invitation with a stale login keeps the form and sends once more; Cancel of the password dialog drops it and keeps the form; no second prompt; Cancel clears the form | `invitations.test.tsx`; mutation check (second prompt allowed) fails it |
| Real stack: an Owner invites from the screen (invalid address, then a good one), reads the pending list, sees the same address refused with one message, the email arrives, Resend mails a new link, Revoke removes a row, the person joins with the newest link and appears in Members while the pending list is empty | `e2e/invitations-ui.spec.ts` |
| Real stack: an Admin sees an Admin invitation without actions, can only pick Viewer or Agent, and can invite; an Agent has no Invite button and the invitations address is "not available" | `e2e/invitations-ui.spec.ts` |
| Axe: pending list (Owner and Admin) and the invite dialog with an error, no serious or critical violations | `e2e/invitations-ui.spec.ts` |
| Screenshots at 360, 768 and 1280 px reviewed (Owner, Admin, dialog at 360); no changes needed | `test-results/f1-4/*.png` (not committed) |

## Checks run (F1-4)
- Vitest 128 passed (12 files), `npm run typecheck`, `npm run lint`, `npm run build`: clean.
- Playwright 25 of 25 passed against the real API jar, the built web app, PostgreSQL and Mailpit, on servers started by Playwright (datasource override in `docs/runbooks/operations/invitations.md`). Existing members tests needed a selector change: the Members page now has two live regions.
- Mutation checks caught and restored: invitation actions (all allowed); the guard against a second password prompt in the invite dialog. A third (removing `.trim()` of the address) was not caught because an `<input type="email">` already strips surrounding spaces, so that trim is redundant defence and is not claimed as tested.
- The unexplained one-off server error noted in F1-3 did not recur in the three full runs of this phase.
- Not run: the stale-login step-up against a real session (cannot be aged in a test; unit tests cover the sequence), the capacity warning (waits for slice 006), `./mvnw verify` (no backend change; once at the end of F1), contract lint and type-drift check (contract unchanged), the production-cookie E2E variant, other browsers, CI.
