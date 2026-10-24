# Design brief: members, invitations and company navigation

- Status: **Draft for review (2026-10-24).** This is a brief with text wireframes, not a visual design. No mockup exists for any of these screens (`docs/design/auth` has only sign in, sign up and profile). Nothing here is approved; the pages need the product owner's review before any build.
- Covers: SPEC-INV (slice 004), SPEC-MEM (slice 003, whose API has no screen yet), SPEC-TEN (company list). Plan: `docs/specs/004-invitations/plan.md`, section 3 (Q-14, Q-20).
- Visual language: follow D-92 and the existing auth pages (`apps/web/src/components/auth/ui.tsx`, `AuthShell.tsx`, tokens in `apps/web/src/app/globals.css`). Heading serif, colors and sizes come from there. There is no signed-in app shell yet; page P5 below proposes the smallest one.

## Page inventory
| ID | Page | Who | Built in | API it uses |
|---|---|---|---|---|
| P1 | Accept invitation (`/invitations/accept`) | Any signed-in user | **Slice 004** | `POST /api/v1/invitations/accept` |
| P2 | Members and invitations (`/companies/{id}/members`) | Owner, Admin | Frontend slice | members (list, role, suspend, reactivate, remove), invitations (create, list, resend, revoke) |
| P3 | Invite dialog (on P2) | Owner, Admin | Frontend slice | `POST .../invitations` |
| P4 | Confirm password dialog (step-up) | Anyone doing a sensitive action | Frontend slice | `POST /api/v1/auth/step-up` |
| P5 | My companies (`/companies`) and a minimal signed-in header | Any signed-in user | Frontend slice (P1 links to it) | `GET /api/v1/companies` |
| P6 | Leave company (confirmation on P5/P2) | Admin, Agent, Viewer | Frontend slice | `POST .../leave` |

Changes to existing pages (slice 004): sign in and sign up return to P1 after success (`next`), and sign up shows "Use the address the invitation was sent to".

## Rules for every page
- **Authorization is never the UI's job.** Hide or disable what a role cannot do, but every call is checked by the API; a 403 or 404 still needs a calm message.
- **No account enumeration, no invitation enumeration.** Failure copy never says whether an address or company exists.
- **No tokens in the page after reading.** P1 reads the fragment once, moves it to the tab's `sessionStorage`, and replaces the URL (AC-INV-20). Nothing logs or displays it.
- **Accessibility:** keyboard-complete, visible focus, labels on every field, errors announced (`aria-live`), dialogs trap focus and return it, status never by color alone. Axe scan in the Playwright run, as for the auth pages.
- **Mobile first** at 360 px; tables become stacked rows.
- **Copy** is plain and short; the same sentence for every "invalid link" reason.

## P1. Accept invitation
Purpose: let the invited person join. One card, centred, like the auth pages.

States and copy
| State | What the user sees |
|---|---|
| Signed out, token present | "You were invited to join a company." Buttons "Sign in" and "Create an account". Note: "Use the address this invitation was sent to." (the page cannot show the address: it does not know it, and must not) |
| Signed in, token present | "Join this company?" [Accept invitation] [Not now]. The company name is not shown before accepting (it would reveal it to the wrong person) |
| Accepting | Button disabled with a spinner, "Joining..." |
| Success | "You joined <company name> as <role>." [Go to my companies] |
| Invalid, expired, revoked, used, wrong address, company unavailable | One message: "This invitation link is invalid or has expired. Ask the person who invited you to send a new one." |
| Already a member | "You are already a member of this company." (409) |
| No room | "This company has reached its member limit. Ask an administrator." (409) |
| Too many attempts | "Too many attempts. Try again later." (429) |
| No token (fragment missing or token in the query string) | The same "invalid" message. A token in the query string is ignored (AC-INV-18) |
| Service unavailable | "Something went wrong. Try again." |

```
+--------------------------------------+
|  <brand>                             |
|                                      |
|  You were invited to join a company  |
|  Use the address this invitation     |
|  was sent to.                        |
|                                      |
|  [ Sign in ]  [ Create an account ]  |
+--------------------------------------+
```

Security: `Referrer-Policy: no-referrer` on this page only; no third-party scripts or images; the fragment is removed before any request.

## P2. Members and invitations
Purpose: one place for slice 003 and slice 004 actions. Two tabs: **Members**, **Pending invitations**. Owner and Admin only (Agent and Viewer get a "not available" page, API returns 403).

```
Company name                                   [ Invite someone ]
[ Members (6) ] [ Pending invitations (2) ]
+-----------------------------------------------------------------+
| Name / email          Role      Status      Joined      Actions |
| Ana Silva (you)       Owner     Active      12 Oct              |
| Bo Chen               Admin     Active      14 Oct      [...]   |
| Cy Dube               Agent     Suspended   15 Oct      [...]   |
+-----------------------------------------------------------------+
Row menu: Change role · Suspend / Reactivate · Remove
```
- Row menus show only what the caller may do (an Admin sees no actions on the Owner or another Admin; the Owner row has none).
- Change role: a select limited to the caller's options; promoting to or demoting an Admin opens P4 first when the session is stale (403 `REAUTHENTICATION_REQUIRED` triggers it and retries).
- Suspend and remove ask for confirmation naming the person; remove says "They will need a new invitation to rejoin."
- A conflict (409) says what changed ("This member was already removed") and refreshes the list.
- Pending invitations tab: address, role, "expires <date>", actions [Resend] [Revoke]. An invitation whose email has not been delivered yet shows "Sending". Revoke confirms.
- Empty states: "No pending invitations."
- Lock timeout (503): "Busy, try again."

## P3. Invite dialog
Fields: **Email** (required, one address), **Role** (Admin only for Owners; Agent, Viewer). Buttons [Send invitation] [Cancel].
- Capacity: when the company is at or near its plan limit, a warning "This company has reached its member limit. People you invite will not be able to join until space is available." Creating is still allowed (AC-INV-26). The warning needs the plan data from slice 006; until then it is not shown.
- Errors: invalid address (field error), already a member (409: "This person is already a member."), already invited (409: "An invitation is already pending. Resend it instead."), rate limit (429: "You have sent too many invitations. Try again later."), Admin role with a stale login (opens P4, then retries).
- Success: dialog closes, the invitation appears in the Pending tab, a toast "Invitation sent to <address>".
- Never shows or asks for a token.

## P4. Confirm password
Purpose: refresh the login time for sensitive actions (AC-AUTH-14): inviting or promoting an Admin now; later ownership transfer, deletion and export.
```
Confirm your password
For your security, enter your password to continue.
[ password ]            [ Continue ] [ Cancel ]
```
Wrong password: the same message as sign in. The action the user started is retried automatically after success; after cancel it is dropped.

## P5. My companies and header
Purpose: after accepting (and after sign in) there is nowhere to go today. Smallest useful shell: a header with the product name, the signed-in user's name, [My companies] and [Sign out] (the profile page already exists), and a list page:
```
My companies
+-------------------------------------------+
| Acme Labs          Owner      [ Open ]    |
| Bo's Studio        Viewer     [ Open ]    |
+-------------------------------------------+
[ Create a company ]   (needs the SPEC-TEN create form: name only)
```
- "Open" goes to the company home, which does not exist yet; for now it opens P2 for Owner and Admin and a short "Company home is coming" page for others. **The company home is a product question, not a design one** (see open questions).
- Empty state: "You do not belong to any company yet. Create one, or open an invitation link you received."
- A company pending deletion appears only for its Owner (API rule).

## P6. Leave company
A confirmation: "Leave <company>? You will lose access immediately. You can only rejoin if someone invites you again." The Owner sees no button, and a note "Transfer ownership first." After success the session cookie is replaced by the API; the page reloads P5.

## Open design questions
1. What is the signed-in home? The documents define no dashboard or company home, and the wireframes in `docs/design/auth` are the only screens. P5 and P2 are the minimum; the product owner decides what a company home contains.
2. Navigation for Owners and Admins: where do "Members" and later "Plan", "Settings", "Ownership" live (side navigation or tabs)?
3. Should P2 show the person's name and address to Admins? The API returns both (D-132).
4. Branding for the invitation email (plain text only, or a styled HTML version).
5. Are the screens to be mocked in Figma, or built directly from this brief with the existing component set?

## Traceability
P1: AC-INV-02, 03, 08, 09, 10, 12, 18, 20, 22, 25, 29. P2 and P3: AC-INV-01, 13, 15, 16, 17, 21, 23, 26, 27, 28 and AC-MEM-01 to 15. P4: AC-AUTH-14, 22, AC-INV-27. P5, P6: SPEC-TEN (multiple companies, creation), AC-MEM-07, 09.
