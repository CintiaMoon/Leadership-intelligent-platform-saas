# Plan: frontend slice F1 (company navigation, members, invitations)

- Status: **Approved 2026-10-25** (user: "approved"): D-172 to D-175, the phase order F1-1 to F1-5 with one PR each, and the landing pages (Agents and Viewers on the placeholder page; the accept page and sign in end on My companies). D-168 to D-171 were decided the same day. F1-1 to F1-5 are implemented; the closure is waiting for the product owner's sign-off.
- Brief: `docs/design/members-and-invitations/README.md` (pages P2 to P6; P1 is built). Origin: D-163 / plan Q-20 of slice 004.
- Specs and APIs it uses (all merged, contract unchanged): SPEC-TEN (create and list companies, get company), SPEC-MEM (members), SPEC-INV (invitations), SPEC-AUTH (step-up, AC-AUTH-14, 22). Related: D-92, D-93, D-102, D-132, D-163.
- Date: 2026-10-25. Written from the brief, the OpenAPI contract and the current `apps/web` code.

## 1. Readiness check
| Item | State | Action |
|---|---|---|
| Blocking decisions in the register | None open for F1 | None |
| The five open design questions in the brief | 1 to 4 answered; 5 (email branding) not part of F1 | D-168 to D-171 record the answers. The invitation email stays plain text (D-155, D-165) |
| API contract | Complete for F1: `listCompanies`, `createCompany`, `leaveCompany`, `listMembers`, `changeMemberRole`, `suspendMember`, `reactivateMember`, `removeMember`, `listInvitations`, `createInvitation`, `resendInvitation`, `revokeInvitation`, `stepUp` | **No API change planned.** If a screen needs one, it stops and comes back for approval |
| Plan data for the capacity warning | Needs slice 006 | The warning is not built (brief P3); a code comment marks the place |
| BFF | Only `/api/auth/*` operations exist, as an exact-key allowlist with GET and POST | Needs new operations with ids in the path and PATCH and DELETE (section 4) |

## 2. Decisions
| ID | Decision | Status |
|---|---|---|
| D-168 | **No company home page.** My companies lists the user's companies. Open goes to Members for Owners and Admins and to a short "Company home is coming" page for Agents and Viewers | Decided 2026-10-25 (user chose "Minimal, no home page") |
| D-169 | **Top header plus a tab row per company.** Header: product name, the user's name, My companies, Sign out. Inside a company, tabs (Members now; Plan, Settings and Ownership are added by their slices). No sidebar | Decided 2026-10-25 (user chose "Top header + tabs per company") |
| D-170 | **Names and email addresses are shown to Owners and Admins** on the Members page, as the API returns them (D-132) | Decided 2026-10-25 (user chose "Yes, to Owners and Admins") |
| D-171 | **Built from the brief** with the existing components, tokens and the ui-design skill; no Figma file is created. Screens are checked in a real browser with Playwright screenshots at 360 px and desktop widths and with axe | Decided 2026-10-25 (user chose "Build from the brief") |
| D-172 🔒 | **BFF additions are an allowlist of operations, not a pass-through.** Each entry is a method plus a path pattern with strict UUID segments; anything else is a 404. GET, POST, PATCH and DELETE; the Origin and `Sec-Fetch-Site` checks, body limit, header and cookie allowlists apply to every non-GET exactly as today. No new cookie, no new header forwarded | Proposed |
| D-173 | **Reads happen on the server, writes in the browser.** Pages are server components that read through the session cookie (like `loadSession`) and render; mutations go from client components through the BFF with the session-bound CSRF token, then the page refreshes. No data cache is shared between users; every page is `no-store` | Proposed |
| D-174 | **Step-up is a dialog that retries the action.** A 403 `REAUTHENTICATION_REQUIRED` opens "Confirm your password"; on success the original request is sent again once; on cancel it is dropped. The password is held only in the form field and is sent only to `/api/auth/step-up` | Proposed |
| D-175 | **Errors are mapped from stable codes, never from server text.** 401 goes to sign in with `next` back to the page; 403 and 404 show one calm "not available" state (they are not told apart for companies the user may not see); 409 says what changed and refreshes; 429 and 503 say to wait; the wording never reveals whether another company or address exists | Proposed |

## 3. Assumptions and trust boundaries
- The browser is untrusted. Hiding a button is a convenience; the API decides every request (CLAUDE.md principle 7). Tests include calls the UI would never make.
- Company, membership and invitation ids in the address are selectors only. The page gets 404 from the API for an id the user may not use and shows the same state as for a missing company.
- The user's role, the plan and the company status are never taken from the browser. The role used to choose which actions to show comes from the API response (`CompanySummary.role`, `MemberSummary`), and a stale role only causes a 403 that the page handles.
- The invitation token is never involved in F1: the pending list has no token and no link (the API never returns one).
- Email addresses of members and invitees are personal data; they are rendered as text only, never put in URLs, titles, logs or error messages.
- CSP (D-102) stays as is: no inline script, no third-party resource, no new `connect-src`.

## 4. Proposed work, in phases (each its own branch and PR)
### Phase F1-1: BFF operations and browser client
Add the operations of section 5 to `bff.ts` as method and path-pattern entries (D-172); generalize `lib/api/client.ts` beyond `POST` of JSON (GET is not needed in the browser because pages read on the server; PATCH, DELETE and body-less POST are). New `lib/server/api.ts` for server-side reads with the session cookie. Unit tests: every entry reaches the right API path with the session and CSRF header and nothing else; wrong method, extra segment, non-UUID id, double slash, encoded slash and `..` are all 404; the existing 404, origin and size tests still pass. **No UI.**

### Phase F1-2: Shell, My companies, create and leave (P5, P6)
Signed-in layout with the header (D-169); `/companies` (list, empty state, "Create a company" form with name only); `/companies/[companyId]` redirect rule from D-168 and the "Company home is coming" page; leave confirmation (Owner sees "Transfer ownership first."); the profile page moves into the shell; sign in and the accept page send people to `/companies` after joining instead of `/account` (the accept page's "Go to my profile" link becomes "Go to my companies"). Unit tests and Playwright: create a company, see it listed, open it, leave as a non-owner.

### Phase F1-3: Members tab (P2, members) and the password dialog (P4)
`/companies/[companyId]/members`: table (stacked rows at 360 px), role select limited to the caller's options, suspend and reactivate, remove with a confirmation naming the person, 409 handling with refresh, the step-up dialog and automatic retry (D-174). Agents and Viewers get the "not available" state (API 403). Unit tests for each action's request and error mapping; Playwright: Owner promotes an Admin through the password dialog, Admin sees no actions on the Owner or another Admin, suspend then reactivate, remove, an Agent opening the URL.

### Phase F1-4: Pending invitations tab and invite dialog (P2 pending, P3)
List (address, role, expiry, "Sending" until the link is issued), resend, revoke with confirmation, invite dialog with the field and role errors of the brief, stale-login step-up for Admin invitations, toast on success. Unit tests; Playwright: invite from the UI, read the email from Mailpit, accept as the new person, see them in Members; resend; revoke; duplicate and member conflicts; 429 message.

### Phase F1-5: Review and closure
Accessibility (axe on every new page and dialog, keyboard-only pass of each flow, focus return from dialogs), responsive screenshots at 360, 768 and 1280 px reviewed by you, an independent read-only security review and code review (BFF allowlist, error mapping, no data in URLs or logs), traceability, `LogHygiene` for the new BFF operations, list of checks not run, closure status for your sign-off.

## 5. BFF operations added (all under `/api/`, all need the session and, for non-GET, the CSRF header)
| Browser call | API operation |
|---|---|
| `POST /api/auth/step-up` | `stepUp` |
| `POST /api/companies` | `createCompany` |
| `POST /api/companies/{companyId}/leave` | `leaveCompany` |
| `PATCH /api/companies/{companyId}/members/{membershipId}` | `changeMemberRole` |
| `POST /api/companies/{companyId}/members/{membershipId}/suspend` | `suspendMember` |
| `POST /api/companies/{companyId}/members/{membershipId}/reactivate` | `reactivateMember` |
| `DELETE /api/companies/{companyId}/members/{membershipId}` | `removeMember` |
| `POST /api/companies/{companyId}/invitations` | `createInvitation` |
| `POST /api/companies/{companyId}/invitations/{invitationId}/resend` | `resendInvitation` |
| `DELETE /api/companies/{companyId}/invitations/{invitationId}` | `revokeInvitation` |

Reads (`listCompanies`, `getCompany`, `listMembers`, `listInvitations`) are made by the server-rendered pages and are not exposed to the browser through the BFF.

## 6. Expected files (approximate)
- `apps/web/src/lib/server/bff.ts` (+ `bff.test.ts`), `lib/server/api.ts`, `lib/api/client.ts`, `lib/api/messages.ts`.
- `apps/web/src/app/(app)/layout.tsx`, `companies/page.tsx`, `companies/[companyId]/page.tsx`, `companies/[companyId]/members/page.tsx`, `app/api/companies/[...path]/route.ts`, `app/api/auth/[...path]/route.ts` (add PATCH and DELETE handlers).
- `apps/web/src/components/app/*` (header, tabs, dialogs, toast), `components/members/*`, `components/invitations/*` (extended), tests beside each.
- `apps/web/e2e/companies.spec.ts`, `members.spec.ts`, `invitations.spec.ts` (extended), `e2e/support.ts`.
- `apps/web/next.config.ts` (no-store for the new pages), docs: this plan, `traceability`, the design brief status.
- **No** change to `contracts/openapi.yaml`, backend code, migrations or Postman.

## 7. Acceptance criteria covered
P2 and P3: AC-INV-01, 13, 15, 16, 17, 21, 23, 26 (the warning part waits for slice 006), 27, 28 and AC-MEM-01 to 15. P4: AC-AUTH-14, 22, AC-INV-27. P5 and P6: SPEC-TEN multiple companies and creation, AC-MEM-07, 09. These criteria are already verified at the API; F1 adds UI evidence for the paths a person can reach.

## 8. Risks
- **A wider BFF is a wider attack surface.** Mitigation: exact method and pattern entries, UUID-only segments, the same origin and size checks, tests for every near-miss path, and a security review in F1-5.
- **A stale role shows a button that fails.** The API refuses; the page shows the calm state and refreshes.
- **Password handling in the step-up dialog.** Held in the field only, sent once, never stored, never in a URL; the form clears on success, cancel and error.
- **Tab or page caching of personal data.** Every page is `no-store`; data is read per request.
- **The signed-in shell is the first one**; later slices will add tabs. Keeping the tab row data-driven avoids reworking it.

## 9. Checks to run
Vitest, typecheck, lint, `next build`, Playwright (including axe and screenshots), contract lint and the type-drift check (they must show no change), and `./mvnw verify` once at the end to prove the backend is untouched. Not run by the assistant unless listed in the traceability.

## 10. Approval
All three items were approved on 2026-10-25.
