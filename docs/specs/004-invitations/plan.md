# Implementation Plan: SPEC-INV (slice 004)

- Status: **Approved 2026-10-24: Q-01 to Q-20** (user: "Q-1 to Q-20 approved"), recorded as D-144 to D-163 in the register (Q-NN maps to D-(143+NN)). SPEC-INV was approved the same day with its amendments. **The phase order was approved on 2026-10-24** (user: "I approve"); Phase 0 is done and Phase 1 is in progress. Nothing is implemented.
- Spec: `docs/specs/004-invitations/spec.md` (SPEC-INV, Draft)
- Related: SPEC-TEN (matrix, pending deletion), SPEC-MEM (membership rules, member limit hook), SPEC-AUTH (verified email, token pattern, fragment links), SPEC-PLAN (member limit), ADR-003 (outbox), ADR-004 (rate limiting), ADR-005 (errors); decisions D-24 to D-27, D-38, D-43, D-44, D-48, D-52, D-55, D-62, D-63, D-93, D-100, D-125, D-127, D-134, D-138
- Date: 2026-10-24. Written from the spec, the register and the slice 001 to 003 code; no code was changed.

## 1. Readiness check (CLAUDE.md workflow step 1)
| Item | State | Action |
|---|---|---|
| SPEC-INV status | Approved 2026-10-24 with the amendments (was Draft) | Done. AC-INV-27 to AC-INV-29 were added and AC-INV-10, 14 and 16 amended in the spec |
| D-24 to D-27, D-38, D-43, D-44, D-48, D-52, D-62, D-63 | Decided | None |
| D-18 (matrix) | Decided; the "Invite" and "Revoke or resend" rows already exist | None |
| D-100 (retention of outbox and audit rows) | Approved; final periods need legal review | Not blocking. The invitation row holds an email address; see Q-04 and the risks |
| Plan limits (slice 006) | Not built | AC-INV-12 gets its mechanism now (`ActiveMemberLimit`, D-134) and closes in 006 |
| Design for invitation screens | None in `docs/design` | Q-14: only the accept page is in scope |
| Slice 003 | Closed 2026-10-23 | 004 changes `MembershipService` (Q-11) |

## 2. Assumptions and trust boundaries
- Browser untrusted; BFF authenticated by its credential; the API is the only authority. The company in a create, list, resend or revoke path is a selector (D-03). The accept route has **no** company in its path: the company comes from the invitation found by the token hash, so acceptance cannot cross company boundaries (AC-INV-06).
- The raw token exists only in the email. The outbox event carries the invitation ID, never the token (the pattern of `VerificationEmails`). The database holds only a hash (AC-INV-05, 19); nothing logs a token or the request body of the accept call (AC-INV-07).
- The invited address is normalized (D-55) and compared with the signed-in user's **verified** normalized address. Following a link never verifies an address (D-63, AC-INV-25).
- Everything that changes a membership or an invitation takes the company row lock first (D-125), then the invitation row, inside one transaction.

## 3. Gaps found, with proposed answers
| ID | Gap | Proposal |
|---|---|---|
| Q-01 | The spec is Draft and has gaps (below) | The product owner approves SPEC-INV including the amendments in Q-05 to Q-08 and Q-11. Until then nothing is implemented |
| Q-02 | No endpoints are defined (examples are not approved until in OpenAPI) | Under `/api/v1/companies/{companyId}`: `POST /invitations` (`{email, role}`, 201), `GET /invitations` (pending ones), `POST /invitations/{invitationId}/resend` (200), `DELETE /invitations/{invitationId}` (revoke, 204). Outside any company: `POST /api/v1/invitations/accept` (`{token}`, 200, returns the company and role). All added to `contracts/openapi.yaml` before the code. Same conventions as slice 003 (canonical UUIDs only, CSRF on every change) |
| Q-03 | AC-INV-21 wants resend to rotate the token in one transaction, but the raw token must not be stored for the email | The invitation row has a nullable `token_hash`. Creating or resending sets it to NULL (any earlier token is dead at once) and enqueues an outbox event with the invitation ID. The delivery handler mints the token, stores its hash only if the invitation is still PENDING and unexpired, and builds the email. Until delivery the invitation cannot be accepted. A retried delivery mints a new token and kills the previous one. Expiry is 7 days from the latest create or resend (amendment: the spec says "7 days after it is issued") |
| Q-04 | Schema | Migration `V13`: `invitation` (`id`, `company_id` FK, `email_normalized`, `email_original`, `role` CHECK in ADMIN, AGENT, VIEWER, `status` CHECK in PENDING, ACCEPTED, REVOKED, EXPIRED, `token_hash` nullable with a CHECK for 64 hex characters, `invited_by` FK to `app_user`, `expires_at`, `created_at`, `updated_at`, `accepted_at`, `accepted_by`, `revoked_at`, `revoked_by`, `version`). Unique index on `token_hash` where not null; **partial unique index on `(company_id, email_normalized)` where status = PENDING** (D-25). Foreign keys RESTRICT (slice 007's purge must delete invitations too). New tables only: no lock on existing ones |
| Q-05 | Who may invite or resend which role; recent authentication | `InvitationRules` (pure): Owner may invite Admin, Agent, Viewer; Admin Agent, Viewer; nobody Owner (D-27, AC-INV-17). Resend and revoke follow the matrix note: an actor handles only invitations for roles that actor may invite. **Amendment:** creating or resending an invitation **for Admin** needs a login or step-up within 10 minutes (D-127): otherwise a stolen Owner session could invite an attacker as Admin without the check that promoting a member needs |
| Q-06 | AC-INV-16 rejects invitations only for an ACTIVE member; a SUSPENDED member also holds a live membership (a second one is impossible by the unique index) | **Amendment:** reject (409 `CONFLICT`) for ACTIVE **and SUSPENDED** members, at creation and at acceptance, so an invitation can never be used to get around a suspension. A REMOVED member can be invited again (a new membership row, history kept) |
| Q-07 | Creating an invitation while one is pending for the same address; expired pending rows block the unique index | A second create for the same company and address is 409 `CONFLICT` (use resend). Under the lock, an expired PENDING row is marked EXPIRED first, so a new invitation can follow an expired one. No sweep is required by the spec; a periodic sweep that marks EXPIRED and writes `invitation.expired` is optional and left out |
| Q-08 | AC-INV-10: what "creates no additional membership" means when the invitee is already a live member at acceptance | The invitee is rejected with 409 `CONFLICT` (only after the address matched, so nothing leaks), the invitation is not consumed, and the inviter can revoke it. No role change is ever made by acceptance for an existing member |
| Q-09 | Accept: errors and the transaction | Needs a session (verified, ACTIVE) and the session-bound CSRF token. Every failure about the invitation itself returns the same 422 `TOKEN_INVALID`: unknown, expired, revoked, consumed, **address mismatch** (AC-INV-22), company pending deletion or gone (AC-INV-09), inviter no longer able to grant the role (Q-11). After the address matched: 409 `CONFLICT` for "already a member" (Q-08) and for "no room" (AC-INV-12). One transaction: look up the company ID by token hash (a plain value); lock the company row; lock the invitation by ID and re-check the hash; check status, expiry, address, company ACTIVE, the inviter's current membership; ask `ActiveMemberLimit`; check for a live membership; insert the ACTIVE membership; mark the invitation ACCEPTED; audit. Concurrent accepts of one token create at most one membership (AC-INV-04) |
| Q-10 | Abuse control (AC-INV-11, AC-INV-23); the limiter can only count hits, not read | Add a read-only `RateLimiter.current(policy, key)`. The accept route **counts failures only** (10 per IP per hour; the 11th failed attempt is throttled with 429), so an office onboarding many people from one address is not blocked by its successes. Sending limits through the existing limiter: 20 per inviter per hour, 50 per company per day, 3 sends per recipient address per 24 hours (key is the HMAC of the normalized address), creation and resend both count, counted before validation. The store being unavailable fails closed (503) |
| Q-11 | D-62 and AC-INV-24: inviter changes | `MembershipService` revokes, in the same transaction as the change, the pending invitations the member created for roles they can no longer grant, when the member is suspended, removed, leaves, or changes role. **Defence in depth:** acceptance also checks that the inviter's membership is ACTIVE and may grant the role now, so a change made outside the service (the operator disable procedure suspends an Owner) cannot leave a live invitation. The operator SQL is not changed |
| Q-12 | The email | Fixed template: company name (plain text), the role, the expiry, and a link `<public base URL>/invitations/accept#token=...` (fragment, D-44). **No inviter name or address.** Sent through the outbox after commit; no network call inside a transaction. Risk accepted: a company name is user-chosen text (D-105, D-122 limit its characters), so a malicious user could try to phish through our sender; the send limits and the fixed template bound it |
| Q-13 | Audit | `invitation.created`, `invitation.resent`, `invitation.revoked`, `invitation.accepted` (names already in the catalogue) and `membership.created` (after_value is the role), with company, actor and target IDs and no address. Automatic revocations (Q-11) are audited as `invitation.revoked` with reason `inviter_changed`. Denials are audited as in slice 002 (D-115, D-121) |
| Q-14 | Frontend scope | Only what the spec requires: the **accept page** (`/invitations/accept`), one BFF route for `accept` added to the allowlist (D-93), and `next` handling so sign-in and sign-up can return to the accept page. The page keeps the token in the tab's `sessionStorage` while the user signs in, so the fragment can be removed at once. A person verifying their address in another tab opens the invitation link again. Visual style follows D-92 and the auth pages. **No management UI** (create, list, resend, revoke) and no BFF routes for them: there is no design, same decision as D-120 and D-138. The Postman collection is extended |
| Q-15 | AC-INV-20 | The accept page (and only it) sends `Referrer-Policy: no-referrer`; the page reads the fragment, stores the token, and replaces the URL without it before any request. A unit test and a Playwright test check both. D-102's CSP rules apply |
| Q-16 | AC-INV-12 and AC-INV-26 (member limit) | Mechanism now: acceptance asks `ActiveMemberLimit` under the company lock (D-134); creation is never capped by capacity. A test with a "full" limit shows the rejection (409) and that concurrent accepts cannot exceed it. The criterion closes in slice 006 with the real limit |
| Q-17 | Tests that must exist beyond the criteria | The invitation lookup by token hash is the only unscoped read, marked `@CrossTenantByDesign`; `TenantRepositoryScopeTest` stays green. `LogHygieneTests` gains the invitation journeys (create, deliver, accept, failed accept: no raw token in any log line). A real-endpoint matrix over four roles for create, list, resend, revoke. Cross-tenant invitation IDs give 404 and change nothing (AC-TEN-08 for invitations) |
| Q-18 | Slice 003 interplay | The accept transaction and the membership operations take the same locks in the same order, tested together (an accept racing a suspend of the inviter; an accept racing a removal of the invited address's existing membership). New audit event `membership.created` is written by the accept path |
| Q-19 | Slice 007 | The purge must delete a company's invitations (they hold email addresses) in the same transaction as the company and memberships. Recorded as a carry-forward |
| Q-20 | Screens beyond the accept page (found by reading the documents; the spec itself says "the interface shows a warning" about plan capacity when inviting, and slice 003's API has no screen) | A separate **frontend slice (F1)**, after the product owner reviews `docs/design/members-and-invitations/README.md`: the Members and invitations page (list, invite dialog with the capacity warning, resend, revoke, change role, suspend, reactivate, remove), the confirm-password dialog (step-up, needs a BFF route and a component), a minimal signed-in header with a My companies page (the accept page's success state needs somewhere to link), and leave company. Each needs BFF allowlist additions (D-93). **Slice 004's build scope does not change**: Phases 1 to 5 deliver the API and the accept page only. Not needed: a page listing the invitee's invitations (impossible by design: the raw token is not stored). The open design questions in the brief (the signed-in home, navigation, visual mockups in Figma or from the brief) are the product owner's |

## 4. Phases (one PR each, in order; each branches from `main` after the previous one is merged)
### Phase 0: Governance (docs only)
Done 2026-10-24: the spec is approved; Q-01 to Q-20 are recorded as D-144 to D-163; the AC-INV criteria (including AC-INV-27 to AC-INV-29) are in `traceability.md` as Pending.

### Phase 1: Schema and persistence
- Migration `V13__create_invitation.sql` (Q-04), reviewed for locks (new table only). Entity and repository (every method scoped by company, except the marked token lookup); `InvitationLocks.lockInvitation` (a new class in `invitation/application`, so the invitation package does not make `CompanyLocks` depend on it; it reuses `CompanyLocks.limitWait`); `RateLimiter.current` and its implementations.
- Tests on PostgreSQL: constraints, the partial unique index (including under concurrency), token-hash uniqueness, CHECKs; the clean-database and prior-data migration check.

### Phase 2: Rules, service, delivery
- `InvitationRules`, `InvitationService` (create, resend, revoke, list), the outbox event and handler that mints the token at delivery, the email template, rate limits, audit.
- Tests: the rules for every actor and role, the lock sequence, token minting at delivery (a retry kills the earlier token; a revoked invitation is never delivered), limits and fail-closed behaviour.

### Phase 3: Management endpoints
Contract first, then the controller for create, list, resend, revoke. Tests for AC-INV-01, 03 (revoked), 13, 15, 16, 17, 21, 23, 26 over HTTP, the real-endpoint matrix, CSRF, cross-tenant IDs, recent authentication for Admin invitations.

### Phase 4: Acceptance
Accept service and endpoint (Q-09), membership creation, `MembershipService` revocation of the inviter's invitations (Q-11), the limit hook (Q-16). Tests for AC-INV-02, 03, 04, 05, 06, 08, 09, 10, 11, 12, 14, 18, 19, 22, 24, 25, and PostgreSQL concurrency (parallel accepts of one token; accept racing a revoke, a resend, and a suspension of the inviter).

### Phase 5: Frontend and end to end
The accept page, BFF route, `next` handling, `Referrer-Policy`, fragment removal (Q-14, Q-15); unit tests; Playwright flows (existing user, new user with Mailpit, wrong address, replay); `LogHygieneTests`.

### Frontend slice F1 (separate; after the design is reviewed)
Pages P2 to P6 of `docs/design/members-and-invitations/README.md` with their BFF routes and Playwright flows. It gets its own short plan when the design is approved. It does not block slice 004's closure; it blocks using invitations without API access.

### Phase 6: Review and closure
Independent security and code review (read-only agents), fixes, traceability, Postman collection and OpenAPI alignment, the list of unrun checks, closure status for the user's sign-off.

## 5. Expected files
`contracts/openapi.yaml`; migration `V13`; in `com.example.saas.api`: `invitation/domain` (`InvitationRules`), `invitation/application` (`InvitationService`, `InvitationAcceptService`, delivery handler), `invitation/persistence`, `invitation/web`, changes to `tenant/application/MembershipService`, `CompanyLocks`, `ratelimit/RateLimiter` and `JdbcRateLimiter`, `auth/email/AuthEmails` (or an `InvitationEmails`), the outbox registration; `apps/web`: the accept page, BFF route, sign-in `next` handling, `schema.d.ts` (the other screens belong to F1); tests under `src/test/.../invitation` and `apps/web` (unit and Playwright); `docs/specs/004-invitations/traceability.md`; `docs/design/members-and-invitations/README.md` (draft); register entries; the Postman collection.

## 6. Acceptance-criteria coverage
| AC | Where |
|---|---|
| AC-INV-01, 17 | Phase 2 (rules), Phase 3 (HTTP) |
| AC-INV-02, 08, 22, 25 | Phase 4 |
| AC-INV-03, 14 | Phase 3 (revoked), Phase 4 (expired, consumed, invalid) |
| AC-INV-04 | Phase 4 (PostgreSQL concurrency) |
| AC-INV-05, 19 | Phase 1 (schema), Phase 2 (token minting) |
| AC-INV-06 | Phase 4 (cross-company), Phase 3 (path IDs) |
| AC-INV-07 | Phase 5 (`LogHygieneTests`) |
| AC-INV-09, 13 | Phase 3 (create), Phase 4 (accept) |
| AC-INV-10 | Phase 4 (Q-08) |
| AC-INV-11, 23 | Phase 2 (limits), Phase 3 and 4 (HTTP) |
| AC-INV-12 | Mechanism in Phase 4 (Q-16); **closed in slice 006** |
| AC-INV-15, 21 | Phase 1 (index), Phase 2, Phase 3 |
| AC-INV-16 | Phase 3 (with the SUSPENDED amendment, Q-06) |
| AC-INV-18 | Phase 4 (a token in the path or query is not accepted: no such route exists, and the body field is the only reader) |
| AC-INV-20 | Phase 5 |
| AC-INV-24 | Phase 4 (Q-11) |
| AC-INV-26 | Phase 3 |
| AC-AUTH-14 (inviting an Admin) | Closed for this consumer in Phase 3 |

## 7. Risks
- The raw token travels in the email and, for a moment, in the accept request body. Neither is logged; the page removes the fragment immediately; the token is single-use and hashed at rest.
- A user-chosen company name appears in an email sent from our domain (phishing risk): bounded by the send limits, the fixed template, no inviter details, and D-122.
- An invitation row holds an address that may never become a user. It is deleted with the company; there is no other retention rule until legal review (D-100). Slice 007 must include invitations in the purge.
- `sessionStorage` holds the token during sign-in; a script running in the page could read it. The CSP (D-102) and the short window are the mitigation; a person who signs in from another tab loses nothing but a click.
- The failure throttle is per IP address: users behind one address share it (10 failures per hour). The successes do not count.
- Until slice 006, accepting is not capped by plan, so a company can exceed its future limit; the hook is in place.
- Until frontend slice F1, an invited person can accept but has no company page to open (the success state links to My companies, which F1 builds), and Owners and Admins can only manage invitations through the API or Postman. Accepting the plan without F1 leaves invitations usable by API clients only.

## 8. Checks that will be run per phase
`./mvnw verify` (Testcontainers PostgreSQL), contract lint, the API-type drift check, web unit tests, typecheck, lint and build, Playwright (Phase 5 on), and the CI jobs. `V13` is validated on an empty database and on one that already holds V1 to V12 data. Checks not run are listed in the traceability file.

## 9. Approval needed
1. **Done 2026-10-24:** approval of SPEC-INV by the product owner, including the amendments: expiry counted from the latest create or resend (Q-03); recent authentication for Admin invitations (Q-05); suspended members also rejected (Q-06); a second create for the same address is 409 (Q-07); an existing live member gets 409 and nothing changes (Q-08).
2. **Done 2026-10-24:** Q-01 to Q-20, in whole (Q-20 was added after the user asked what other pages the spec needs). Most worth a deliberate look: Q-03 (the token is minted at delivery), Q-09 (the uniform 422 and the two 409 cases), Q-10 (failures-only throttle and the new read method on the limiter), Q-11 (accept-time re-check of the inviter), Q-14 (accept page only, no management UI).
3. **Done 2026-10-24:** the phase order.
