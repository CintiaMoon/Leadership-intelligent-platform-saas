# Implementation Plan: SPEC-OWN (slice 005)

- Status: **Approved 2026-10-25: Q-01 to Q-19** (user: "I approve Q-01 to Q-19"), recorded as D-176 to D-194 in the register; this includes the SPEC-OWN amendments of Q-19 and the phase order of Q-01. Phase 0 and Phase 1 are done in one pull request; nothing else is implemented.
- Spec: `docs/specs/005-ownership-transfer/spec.md` (SPEC-OWN, Draft; the decisions D-28 to D-32, D-43, D-54, D-64 inside it were decided 2026-10-16/17).
- Related: SPEC-TEN (matrix, pending deletion), SPEC-MEM (membership rules, the ownership flag, D-135, D-141, D-142), SPEC-INV (the listener of slice 004), SPEC-AUTH (recent authentication, D-15, D-89), SPEC-DEL (slice 007, D-31, D-143), ADR-003 (outbox), ADR-005 (errors); D-125 and D-137 (locks, early refusal), D-164/D-165 (limits and email text patterns), D-172 (BFF allowlist).
- Date: 2026-10-25. Written from the spec, the register and the code of slices 001 to 004 and F1; no code was changed.

## 1. Readiness check (CLAUDE.md workflow step 1)
| Item | State | Action |
|---|---|---|
| SPEC-OWN status | Draft; its decisions are decided | Q-19 lists the amendments needed before it can be Approved |
| Decisions that block slice 005 | None open: D-28 to D-32, D-43, D-54, D-64, D-135, D-142 are decided | None |
| Slice 003 | Closed 2026-10-23: single-Owner partial unique index `uq_membership_single_owner` and the deferred trigger `assert_company_has_owner` (V10), the flag `company.ownership_resolution_at` (V12), `CompanyLocks`, `MembershipService`, the audit and outbox patterns | Reused as they are |
| Slice 004 | Closed 2026-10-25: `MembershipChangeListener` revokes a member's pending invitations when their role changes | Completion calls it for the old Owner (Q-14) |
| D-142 operator transfer | Design decided; runbook is a stub | Written, scripted and tested in this slice (Q-15) |
| Slice 007 (deletion) | Not built | This slice provides the hook; AC-OWN-09's deletion half closes there (Q-13) |
| Design for the Ownership screens | None in `docs/design` | Section 7 is a short brief; building follows D-171 (from the brief, no Figma) |

## 2. Assumptions and trust boundaries
- The browser is untrusted. A company, transfer or membership ID in a path or body is a selector only. The caller's role, the recipient's eligibility and the transfer's state are always read from the database under the locks.
- Only the Owner initiates and cancels; only the named recipient accepts. Everyone else gets 404 for the transfer (they must not learn that one exists), and a non-member gets 404 for the company (D-108).
- Acceptance is an authenticated action in the application. The emails carry no token or secret, only a link to a page that needs a login (SPEC-OWN).
- The Owner's disabled-account case is not an API case: the flagged company has a SUSPENDED Owner, who cannot act (D-54, D-142); only the operator script of Q-15 changes it.
- Roles are read from the membership on every request, so a role change takes effect on the next request without touching sessions. AC-AUTH-23 asks for the session identifier of the person whose role changed to be rotated; the person who acts (the recipient on acceptance) gets a new identifier in the response, as leaving a company already does (`SessionManager`). The demoted Owner is not making a request, so there is nothing to rotate; their next request already sees Admin.

## 3. Decisions and answers to the open points of the spec (proposed)
| # | Topic | Proposal (decision) |
|---|---|---|
| Q-01 | Scope | Backend (Phases 1 to 4: schema, service and sweep, contract and API, operator script), frontend (Phase 5: Ownership page, banner, three BFF operations), review and closure (Phase 6). Each phase is its own PR, each needs your approval to start |
| Q-02 | Data model | Flyway `V14__create_ownership_transfer.sql`: table `ownership_transfer(id, company_id, initiated_by, recipient_id, status, created_at, expires_at, resolved_at, resolved_by, resolution)`; FKs RESTRICT; CHECKs: status in PENDING, COMPLETED, CANCELLED, EXPIRED; `initiated_by <> recipient_id`; `expires_at > created_at`; PENDING if and only if `resolved_at` is null; `resolution` in accepted, cancelled_by_initiator, cancelled_company_deletion, cancelled_operator_transfer, expired. **Partial unique index** `uq_ownership_transfer_pending ON (company_id) WHERE status = 'PENDING'` (AC-OWN-10). Indexes for the recipient and for the sweep. No data to backfill. The repository extends `Repository`, every query names the company, and the two that cannot (the sweep's candidate list, the caller's own incoming lookups if any) are marked `@CrossTenantByDesign` |
| Q-03 | API | `POST /api/v1/companies/{companyId}/ownership-transfers` (body: `recipientMembershipId`) creates; `GET .../ownership-transfer` returns the company's pending transfer (404 if none); `GET .../ownership-transfers/{transferId}` returns one; `POST .../{transferId}/accept`; `POST .../{transferId}/cancel`. Contract first, DTOs only. Codes: 201/200; 400 malformed; 401; 403 `FORBIDDEN` (not the Owner, or the company is not ACTIVE) and `REAUTHENTICATION_REQUIRED`; 404 (not a member, not a party, unknown id); 409 `CONFLICT` (already pending, not pending any more, overdue, recipient not eligible, initiator no longer the Owner); 429; 503 with `Retry-After` on a lock timeout |
| Q-04 | Who can read | The initiator (the Owner) and the recipient; everybody else gets 404. An overdue pending transfer is shown as `EXPIRED` when read (AC-OWN-16); reading never writes. The response holds IDs, status, the two display names, dates and no email address of the other party |
| Q-05 | Recipient eligibility | An ACTIVE membership of this company (any role except the initiator's own) whose account is ACTIVE with a verified email. Checked at initiation (409 otherwise; a membership ID that is not in this company is 404) and again at acceptance under the locks (AC-OWN-08). A recipient who is an Agent or a Viewer becomes Owner; the initiator chose them (D-29) |
| Q-06 | Matrix | One new permission row, "Accept ownership transfer": allowed for all four roles in the matrix, with "only the named recipient" enforced by the service (like Leave, it is identity-based); SPEC-TEN's table and the parity test get the row. Cancel keeps its row (Owner) plus "only the initiator". There is no "decline" (the spec has none): the recipient can ignore it, or ask the Owner to cancel; a decline can be added later |
| Q-07 | Locks | Early refusal before any lock for roles that can never do the operation (D-137). Then company row, transfer row, then the two memberships in ID order, all in one transaction with `SET LOCAL lock_timeout` as before (D-32, D-125); a lock timeout is 503 with `Retry-After`. Entities are loaded after the locks |
| Q-08 | Completion | As in the spec, in one transaction: transfer PENDING and not overdue; company ACTIVE; the initiator still the ACTIVE Owner (AC-OWN-15); the recipient still eligible; demote the old Owner to Admin and flush, promote the recipient to Owner and flush (the unique index and the deferred trigger hold at every commit); mark COMPLETED with `resolved_by`; audit; outbox events; the listener of Q-14. Nothing external inside the transaction. Any failure rolls everything back (tested with an injected failure) |
| Q-09 | Expiry | Acceptance and cancel reject an overdue transfer with 409 even if the sweep has not run (AC-OWN-16). Initiating first marks an overdue pending transfer EXPIRED in the same transaction (otherwise the unique index would block a legitimate new one; the pattern of AC-INV-28) and queues its expiry emails. A **sweep** (Spring scheduler, hourly, configurable) lists overdue pending transfers as plain values and expires each in its own short transaction that locks the company row first and re-checks (so it never inverts the lock order and two instances cannot double-expire); a lock timeout skips that one until the next tick |
| Q-10 | Notifications | Four events through the outbox (initiated, completed, cancelled, expired), one outbox row per addressee: the recipient on initiation; the initiator and the recipient on completion, cancellation and expiry (SPEC-OWN, D-64). Payloads hold identifiers only and are scrubbed once sent (D-100). The handler reads the company name and the address at send time. Fixed subject ("Ownership transfer update"), the company name only in the body, in quotes (D-165 pattern), no person names, no token; a link to `<publicBaseUrl>/companies/{id}/ownership`. A retried send sends the same text (AC-OWN-06) |
| Q-11 | Recent authentication | Initiate and accept need a login or step-up within 10 minutes (403 `REAUTHENTICATION_REQUIRED`, AC-OWN-11, AC-OWN-12), checked before any lock. Cancel does not (it only removes a pending change; protective) |
| Q-12 | Abuse limit | At most 5 transfers initiated per company per 24 hours, read before the request and counted after it commits (D-164 pattern, so refused requests do not use it up); 429 with `Retry-After`; an unavailable counter fails closed (503). The spec is silent; without a limit the Owner can loop initiate and cancel to email a member without end |
| Q-13 | Deletion (D-31, D-38) | Initiating while the company is PENDING_DELETION is refused (the policy already blocks state changes there, 403). `OwnershipTransfers.cancelPendingForDeletion(companyId, actor)` cancels a pending transfer with an audit entry and its emails; it is a hook for slice 007 to call inside the deletion request's transaction (company lock already held), tested here by calling it. AC-OWN-09's second half therefore closes in slice 007 |
| Q-14 | Invitations (slice 004) | Completion calls the `MembershipChangeListener`s for the old Owner (role now Admin) and for the new Owner, in the same transaction, so the old Owner's pending invitations for the Admin role are revoked (`inviter_changed`, an Admin may not grant Admin), the rest are kept |
| Q-15 | Operator transfer (D-142) | `docs/runbooks/operations/sql/operator-ownership-transfer.sql`, same shape as the disable and reversal scripts (required `ticket`, normalized address, 5-second lock timeout, stops with an error on any bad input, guards written as `1 / count(*)`): one transaction; locks the company first; requires the flag; recipient is an ACTIVE Admin (or, only with an explicit `non_admin_ok` parameter for the case the security owner names, an ACTIVE member) with an ACTIVE account and a verified email; old Owner becomes Admin and stays SUSPENDED; recipient becomes ACTIVE Owner; flag cleared; any pending transfer cancelled (`cancelled_operator_transfer`); audit `ownership.transfer.operator_completed` (ticket, database role) and `company.ownership_resolution.cleared`; outbox events to the new Owner and the company's Admins, never the old Owner's address. A PostgreSQL test runs the file through the existing `RunbookSql` helper with mutation checks on every guard. The runbook is finished (status Draft until rehearsed). I rehearse it locally with real `psql` on a copy of the development database and record that; **the staging rehearsal that D-142 asks for is yours to run** and gates routine use of the disable procedure |
| Q-16 | Audit | `ownership.transfer.initiated`, `.completed`, `.cancelled`, `.expired`, plus `membership.role.changed` for both memberships on completion (before and after role, actor the recipient) and `ownership.transfer.operator_completed`. Company, actor and target IDs only, no addresses; denials as before (D-115, D-121) |
| Q-17 | Frontend (Phase 5) | An Ownership tab for the Owner (choose an eligible member, confirm the password, see the pending transfer with its expiry, cancel it) and an accept card for the recipient on the same page; a banner on the company page and on Members for the recipient while a transfer is pending; three new BFF entries (initiate, accept, cancel) in the D-172 allowlist; server reads for the rest; the screens of section 7 |
| Q-18 | Tests beyond the criteria | Real PostgreSQL: concurrent accepts of one transfer (one wins); two simultaneous initiations (one wins); accept racing cancel; accept racing the recipient's suspension, removal and leaving; accept racing the Owner's account disable (the flag); initiation racing the sweep; the ownership race repeated through the service (D-140); exactly one Owner after every committed step, checked in the database; rollback after an injected failure after the demotion; the sweep and expiry at exactly 7 days with the injectable clock; a real-endpoint matrix over four roles and the recipient; cross-company IDs; `TenantRepositoryScopeTest` stays green; log hygiene for the new emails; Playwright: the whole journey with two real accounts and Mailpit |
| Q-19 | Spec amendments | SPEC-OWN becomes Approved with: AC-OWN-18 (the operator transfer of D-142 and its tests), AC-OWN-19 (eligibility includes an ACTIVE account and a verified email, at initiation and acceptance), AC-OWN-20 (the initiation limit), AC-OWN-21 (completion revokes the old Owner's pending invitations for roles they can no longer grant), AC-OWN-22 (only the initiator and the recipient can read a transfer; everyone else gets 404), AC-OWN-23 (a hook cancels a pending transfer for deletion, audited). SPEC-TEN's matrix gains the "Accept ownership transfer" row |

## 4. Phases
### Phase 0: this plan (done when you approve it)
Register rows D-176 to D-194; SPEC-OWN and the SPEC-TEN matrix amended as in Q-19.

### Phase 1: schema and persistence
`V14`, the `OwnershipTransfer` entity, the repository (company-first queries, `lockByCompanyIdAndId`), `OwnershipTransferLocks`, schema tests on PostgreSQL (the partial unique index under parallel inserts, every CHECK, the interplay with `uq_membership_single_owner` and the deferred trigger), and the migration validated on an empty database and on one that holds V1 to V13 data. No API yet.

### Phase 2: rules, service, sweep, notifications
Pure rules (`TransferRules`), the service (initiate, cancel, accept, read, `cancelPendingForDeletion`), the rate-limit policy, the audit events, four outbox events with handlers and the emails, the sweep, the listener call. Service-level tests including every concurrency case of Q-18.

### Phase 3: contract and API
OpenAPI first, then controller and DTOs, `SecurityConfig` matchers, the matrix row and parity test, API tests with real HTTP (roles, recipient, cross-company IDs, recent authentication, errors), session rotation on acceptance, Postman requests, the deletion hook documented in the contract notes.

### Phase 4: operator transfer
The script, its PostgreSQL tests with mutation checks, the finished runbook, the outbox events and handler for its emails, the local `psql` rehearsal recorded.

### Phase 5: frontend
BFF entries, the Ownership page and banner, unit tests, Playwright (initiate, accept, cancel, expiry shown, recipient role variants, errors), axe, keyboard pass, screenshots at three widths.

### Phase 6: review and closure
Read-only security and code reviews, fixes, traceability, list of unrun checks, closure status for your sign-off.

## 5. Expected files (approximate)
- Backend: `V14__create_ownership_transfer.sql`; `tenant/ownership/**` (domain rules, persistence, application service and sweep, outbox handlers, web controller and DTOs); `CompanyPolicy`, `Permission` (one row); `AuthEmails` (four messages); `SecurityConfig` (matchers); tests under `src/test/.../tenant/ownership/**`.
- Contract and clients: `contracts/openapi.yaml`, `apps/web/src/lib/api/schema.d.ts`, Postman collection.
- Frontend: `lib/server/bff.ts` (three entries), `components/ownership/**`, `app/(app)/companies/[companyId]/ownership/page.tsx`, banner in the company and Members pages, `CompanyHeader` (the tab), tests and e2e specs.
- Operations and docs: `docs/runbooks/operations/sql/operator-ownership-transfer.sql`, `operator-ownership-transfer.md`, the design addendum, `docs/specs/005-ownership-transfer/traceability.md`, the register, the SPEC-OWN and SPEC-TEN amendments.

## 6. Acceptance-criteria coverage
| Criteria | Where |
|---|---|
| AC-OWN-01, 11 | Phases 2 and 3 (role matrix over HTTP, recent authentication) |
| AC-OWN-02, 12 | Phases 2 and 3 (recipient only, recent authentication) |
| AC-OWN-03, 14, 16 | Phase 2 (repeat acceptance, expiry at 7 days with the clock, overdue on read and on accept before the sweep) |
| AC-OWN-04, 05, 10, 13, 15 | Phase 2 (all the races, exactly one Owner checked in the database, the second pending refused under concurrency, completion preconditions) |
| AC-OWN-06, 17 | Phases 2 and 4 (outbox, retry-safe, no secrets, scrubbed) |
| AC-OWN-07 | Phase 2 and 3 (initiator cancels, a cancelled transfer cannot be accepted) |
| AC-OWN-08, 19 | Phase 2 (eligibility at initiation and at acceptance) |
| AC-OWN-09 | Phase 2 (initiation refused while PENDING_DELETION; the hook); **second half closes in slice 007** |
| AC-OWN-18 | Phase 4 |
| AC-OWN-20, 21, 22, 23 | Phases 2 and 3 |

## 7. Frontend brief (Phase 5)
- **Ownership tab** (Owner only, plus the recipient while a transfer is pending). Owner, nothing pending: "Transfer ownership" with a short warning ("You will become an Admin. The new Owner can remove you."), a select of eligible members (name and role; never the Owner themselves), a button that opens the password dialog (P4) and then creates the transfer. Owner, pending: "Waiting for <name> to accept. Expires <date and time UTC>." and [Cancel transfer] (confirmation naming the person). Overdue shows "Expired" and offers a new transfer.
- **Recipient** (any role, same address): "<Owner name> asked you to become the Owner of <company>. You will become the Owner and the current Owner becomes an Admin." [Accept ownership] (password dialog, then the page reloads showing the new Owner view) and, for now, no decline button (Q-06). If the transfer has expired or been cancelled: one calm message.
- **Banner** on the company page and on Members while a transfer is pending for the viewer, linking to the Ownership page; none for anybody else.
- Messages come from stable codes (D-175): 409 "This transfer changed. The page was refreshed.", 403 REAUTH opens the password dialog and retries once (D-174), 429 and 503 say to wait. Accessibility as for the other screens: names, focus return, axe, keyboard-only pass.
- Not built: a list of incoming transfers across companies (the email link is the way in); notifications other than email.

## 8. Risks
- **The two-membership update is the riskiest write in the system.** Mitigated by the existing unique index and deferred trigger, the lock order, demote-then-promote with flushes, an injected-failure rollback test, and the race tests. The operator script does the same by hand, so it gets the same tests.
- **A malicious or mistaken recipient.** The Owner chooses any active member (D-29); the first screen warns, the recipient must accept with a fresh login, the old Owner becomes an Admin (D-28) but can be removed by the new Owner. Accepted by the decisions already made.
- **The operator script can take over a company.** Two people, a ticket with evidence and consent, checks in the script, an audit event with the ticket and database role, and the new Owner and Admins are told. The staging rehearsal gates routine use (D-142).
- **Spam through initiate and cancel.** Bounded by Q-12.
- **A transfer left pending past its expiry between sweeps.** Acceptance and cancel reject it, reads show it expired, initiation expires it first; only the emails wait for the sweep.
- **Email to a member discloses the company name**, which they already know (they are members). It is placed in the body only and quoted.
- The Ownership page is the first place the recipient sees the Owner's display name; it is a co-member's name already shown on Members for Owners and Admins, and is shown to the recipient only in this context.

## 9. Checks to run
`./mvnw verify` (Testcontainers PostgreSQL), contract lint and the API-type drift check, web unit tests, typecheck, lint, build, Playwright, the CI jobs, `V14` validated on an empty database and on one with V1 to V13 data, the operator script run with real `psql`. Checks not run are listed in the traceability file.

## 10. Approval (done 2026-10-25)
1. Approve Q-01 to Q-19 as written, or change any of them. Most worth a deliberate look: Q-06 (no decline, one new matrix row), Q-12 (a limit the spec does not have), Q-13 (the deletion half closes in slice 007), Q-15 (who runs which rehearsal).
2. Approve SPEC-OWN with the amendments of Q-19 and the new SPEC-TEN matrix row.
3. Approve the phase order and that each phase is its own PR.
