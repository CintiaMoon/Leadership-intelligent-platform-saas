# Implementation Plan: SPEC-PLAN (slice 006)

- Status: **Proposed. Not approved.** Nothing is implemented. Questions Q-02, Q-05, Q-09 and Q-12 need your decision, not just your approval; Q-08 and Q-11 interpret the spec and are worth a deliberate look.
- Spec: `docs/specs/006-plans-entitlements/spec.md` (SPEC-PLAN, Draft; the decisions D-33 to D-36, D-45 to D-48, D-65 to D-67 inside it were decided 2026-10-16/17).
- Related: SPEC-TEN (matrix, company creation, export row), SPEC-MEM (AC-MEM-17, `ActiveMemberLimit`, D-134), SPEC-INV (AC-INV-12, AC-INV-26, D-159), SPEC-OWN (transfer allowed while read-only), SPEC-DEL (slice 007: deletion allowed in every state, purge), ADR-003 (outbox), ADR-005 (errors); D-125 and D-137 (locks, early refusal), `docs/architecture/audit-events.md` (`plan.subscription.transitioned`, `plan.entitlement.changed`).
- Date: 2026-10-27. Written from the spec, the register and the code of slices 001 to 005; no code was changed.

## 1. Readiness check (CLAUDE.md workflow step 1)
| Item | State | Action |
|---|---|---|
| SPEC-PLAN status | Draft; its decisions D-33 to D-36, D-45 to D-48, D-65 to D-67 are decided | Q-21 lists the amendments needed before it can be Approved |
| **D-68 (core domain has no spec)** | **Open** in the register, but its deferral is decided (2026-10-17): this slice delivers the member limit, the trial and subscription, and the entitlement service; the team and assessment limits wait for the domain spec | **Your decision (Q-02):** treat the deferral as enough to start, and keep D-68 open for slice 007 and the quotas it defers |
| Slice 003 and 004 hooks | `ActiveMemberLimit` (default `UnlimitedActiveMembers`) is called on reactivation under the company lock (D-134) and at invitation acceptance (D-159) | This slice supplies the real implementation (Q-11) and closes AC-MEM-17, AC-INV-12 and AC-INV-26 |
| Slice 005 | Closed 2026-10-27. Ownership transfer is allowed while a trial is read-only (SPEC-PLAN) | Gated by the same table (Q-08, Q-09) |
| Slice 007 (deletion) | Not built | This slice only guarantees that deletion request and cancellation are allowed in every state, and that the trial grant outlives a purge (Q-22) |
| Billing | Out of scope for the MVP (D-33, D-35); PAST_DUE and CANCELED reserved | Not built; the model leaves room (Q-03) |
| Export, teams, assessments, questionnaires | No such operations exist yet | Their rows in the matrix are tested through the existing test routes (`PermissionRoutesController`), as every row so far (Q-20) |
| Design for the plan screens | None in `docs/design` | Section 7 is a short brief; building follows D-171 (from the brief, no Figma) |

## 2. Assumptions and trust boundaries
- The browser is untrusted. A plan code, a state, a limit or an entitlement in a request is ignored: there is no endpoint that accepts one (AC-PLAN-03). Plan changes exist only as an operator script.
- Every decision (state, limits, features, usage) is read from the database on the server, with the injected clock, at the time of the request. A scheduled job may record a transition, but no decision depends on it (AC-PLAN-10).
- A company ID in a path is a selector only; a non-member gets 404, as everywhere (D-108). A member whose role may not see the plan gets 403 on the plan endpoint (AC-PLAN-08).
- Time is UTC (`Instant`, `timestamptz`). The trial lasts exactly 14 times 24 hours from the creation instant of the first company; it is expired at and after the end instant (D-34, AC-PLAN-18).
- The plan tables are reference data: written only by Flyway migrations and read by the application. The application never updates them, and a trigger refuses it (Q-03).
- The subscription row is written by the application (at company creation and by the sweep) and by the operator script, always under the company lock.

## 3. Decisions and answers to the open points of the spec (proposed)
| # | Topic | Proposal (decision) |
|---|---|---|
| Q-01 | Scope | Backend (Phases 1 to 4: schema and seed, domain and service and sweep, gating and contract and API, operator script), frontend (Phase 5: Plan page, banners, restricted states), review and closure (Phase 6). Each phase is its own PR and needs your approval to start. Not built: billing, a plan-change API, teams and assessments and their limits, export (no such operations exist), AI credits (D-46), emails about the trial |
| Q-02 | D-68 | **Decision for you.** The register keeps D-68 Open although its deferral is decided. Proposal: record the deferral as the decision that unblocks 006 (register row becomes "Partly decided"), and leave the domain spec as the open part, blocking the team and assessment quotas and slice 007's purge manifest. If you would rather write the domain spec first, 006 waits |
| Q-03 | Data model | Flyway `V15__create_plans_and_subscriptions.sql`. Catalog: `plan(id, code, version, name, status, created_at)` unique on (code, version); `plan_limit(plan_id, limit_key, value)` and `plan_feature(plan_id, feature_key)` with CHECKs on the allowed keys (limits: `TEAMS`, `ACTIVE_MEMBERS`, `ACTIVE_ASSESSMENTS`; features: the eight in the spec). Rows of a plan version are immutable (a trigger refuses UPDATE and DELETE; a change is a new version, AC-PLAN-16). Seed: Trial v1 (Team features, 3/15/3), Starter v1 (3/15/2, its features), Team v1 (15/100/10, all features). Subscription: `company_subscription(company_id unique FK, plan_id nullable FK, state, trial_started_at, trial_ends_at, read_only_ends_at, state_changed_at, created_at, updated_at, version)`; CHECKs: state in the five MVP states, a plan for TRIALING and ACTIVE only, the trial timestamps only for subscriptions that began as a trial, `read_only_ends_at = trial_ends_at + 14 days`. Billing columns are not added; PAST_DUE and CANCELED are not allowed by the CHECK until a billing slice adds them |
| Q-04 | One Trial per user | `user_trial_grant(user_id primary key, company_id, granted_at)` with no foreign key to the company, so a purge (slice 007) does not remove it and a deleted first company does not give the user a second Trial (D-45 says "the first company a user creates"). At company creation: `INSERT ... ON CONFLICT DO NOTHING`; one row inserted means TRIALING, otherwise NO_PLAN. Two simultaneous first creations by one user: the primary key lets exactly one win. Same transaction as the company, so a failed creation leaves no grant and no subscription (AC-PLAN-09) |
| Q-05 | **Companies that exist before the migration** | **Decision for you.** Every existing company needs a subscription row. Proposal: all of them start NO_PLAN (read-only until an operator assigns a plan, with the Q-15 script), because starting a Trial retroactively, or choosing which company is "first" for a user, is a product decision. The cost: the demo and development companies become read-only until a plan is assigned. Alternative: grant a Trial from the migration time to each user's earliest company. Tested on a database holding data from V1 to V14 |
| Q-06 | State evaluation | One pure function `SubscriptionState.effective(persisted, trialEndsAt, readOnlyEndsAt, now)`: TRIALING while `now < trialEndsAt`; EXPIRED_READ_ONLY while `trialEndsAt <= now < readOnlyEndsAt`; EXPIRED_LOCKED from `readOnlyEndsAt`; ACTIVE and NO_PLAN as stored. Used by every decision, so a request after the end instant is expired even if no job ran (AC-PLAN-10, 18). Reading never writes. A sweep (hourly, `app.plan.sweep-interval`, same pattern as the ownership sweep) records the stored transition under the company lock with an audit event; it is idempotent and not needed for correctness (AC-PLAN-11) |
| Q-07 | Transitions | Exactly those of the spec: TRIALING to EXPIRED_READ_ONLY to EXPIRED_LOCKED by time; TRIALING, EXPIRED_READ_ONLY, EXPIRED_LOCKED or NO_PLAN to ACTIVE, and ACTIVE to ACTIVE with another plan, by operator assignment. Any other is refused (AC-PLAN-17); repeating one leaves the state unchanged and writes no second audit event (AC-PLAN-11). A `SubscriptionTransitions` class holds the table and is tested cell by cell |
| Q-08 | The gating table | The matrix of SPEC-TEN gets a second dimension, the effective subscription state, in `CompanyPolicy.permits` (the same place the pending-deletion rule lives), so one pure function answers role, permission, company status and subscription state, and the parity test compares it with the spec. Section 3a lists every permission in every state, from the SPEC-PLAN table; the rows the spec leaves ambiguous are marked |
| Q-09 | **EXPIRED_LOCKED** | **Decision for you.** The spec says that in EXPIRED_LOCKED "everything else is rejected" except Owner reads and deletion request or cancellation. Read literally that also rejects leaving a company, removing or suspending a member, and every ownership-transfer action. Proposal: keep the spec's text, and flag the consequence: a member cannot leave a locked company and a pending transfer cannot be cancelled. Alternative (recommended): also allow Leave, member removal and suspension, and cancelling a pending transfer in EXPIRED_LOCKED, because they only reduce exposure or withdraw a pending change; initiating and accepting a transfer stay rejected. Needs an SPEC-PLAN amendment either way |
| Q-10 | Where the checks run | In the authorization service, next to the role check: read the subscription state with the injected clock, refuse early (before any lock) what the state never allows (D-137), and for changes check again under the company lock, because the lock is where the state is stable against an operator plan change. A refusal is 403 with the new stable code `PLAN_RESTRICTED` and the state as an extension member (the caller is a member and may know it); it is audited like a permission denial (`reason_code` `plan_restricted`, the permission and the state). Invitation acceptance is not in the matrix: it is checked in its own transaction under the company lock, together with the member limit (D-159) |
| Q-11 | Member limit | `PlanActiveMemberLimit` replaces `UnlimitedActiveMembers`: under the company lock it counts memberships with status ACTIVE and compares with the `ACTIVE_MEMBERS` limit of the subscription's plan version; reactivation and invitation acceptance are the only operations that add an ACTIVE member (D-48; suspended members and directory people are not counted). Over the limit: 409 with the new code `MEMBER_LIMIT_REACHED` (the invitation page keeps its own wording). Creating an invitation is never capped (D-159). In states with no plan (NO_PLAN, expired) the gating of Q-08 refuses first. Because the lock serializes, N simultaneous acceptances or reactivations at limit minus one admit exactly one (AC-PLAN-05, tested on PostgreSQL) |
| Q-12 | **Teams and assessments** | **Decision for you.** They do not exist (D-68). Proposal: the limits are in the catalog and returned by the plan endpoint with `enforced: false` and no usage, and the entitlement service has `LimitKey.TEAMS` and `ACTIVE_ASSESSMENTS` with the check method ready, so the domain slice only calls it. AC-PLAN-05 and AC-PLAN-14 are closed for the member limit in this slice and stay open for the other two (as the spec says). Alternative: leave them out of the response until they can be enforced |
| Q-13 | API | `GET /api/v1/companies/{companyId}/subscription` (Owner and Admin; 403 `FORBIDDEN` for Agent and Viewer, 404 for a non-member): plan code, name and version, the effective state, `trialEndsAt`, `readOnlyEndsAt` (when relevant), the features (keys), and the limits (key, value, `enforced`, and `usage` for active members). No write endpoint exists, so a plan change by any role cannot be requested (AC-TEN-12 is tested through the existing `MANAGE_PLANS` test route and by the absence of any route). Contract first, DTOs with exact fields, new error codes `PLAN_RESTRICTED` (403) and `MEMBER_LIMIT_REACHED` (409) in the `ErrorCode` enum |
| Q-14 | Existing operations that change | Members: change role, suspend, remove, reactivate, leave. Invitations: create, resend, revoke, accept. Ownership: initiate, cancel, accept. Company: read, list, settings (none yet). Each gets its state from the table of section 3a through the central policy, not scattered conditionals. The companies list is unchanged (a company is listed in every state; opening it as a non-Owner in EXPIRED_LOCKED says the plan has ended) |
| Q-15 | Operator plan assignment (D-35) | `docs/runbooks/operations/sql/operator-assign-plan.sql`, same shape and safeguards as the other operator scripts (`ticket`, single-transaction marker, 5-second lock timeout, guards written as `1 / count(*)`, final consistency guard, audit with the ticket and `session_user`): variables `company`, `plan` (`STARTER` or `TEAM`), `ticket`; locks the company row first; validates the transition (Q-07); sets the plan version to the latest version of that plan; state ACTIVE; takes effect at once; a downgrade over the limits changes nothing else (Q-18). Writes `plan.subscription.transitioned` and, when the plan changes, `plan.entitlement.changed`. A PostgreSQL test runs the file through `RunbookSql` with mutation checks on each guard; the runbook `plan-change.md` is finished (status Draft until rehearsed); **the staging rehearsal is your team's**, as for the other operator scripts |
| Q-16 | Audit | `plan.subscription.transitioned` (company, before and after state, reason `trial_started`, `trial_expired`, `read_only_ended`, `operator_assigned` or `operator_changed`, actor `system` or `operator`, ticket for operators) and `plan.entitlement.changed` (company, before and after plan code, version in the reason). Denials of the gating are audited as the other denials. No addresses or names |
| Q-17 | Notifications | None. The spec asks for no email about the trial or its end, and sending one is a product decision. The Owner and Admin see the state on the Plan page and in banners (Q-19) |
| Q-18 | Downgrade over the limit (AC-PLAN-14) | Applies to the member limit now: after an operator moves a company to a plan whose limit is below its ACTIVE members, nothing is removed or suspended, reads continue, and reactivation and acceptance are refused with `MEMBER_LIMIT_REACHED` until usage is below the limit again. The Plan page shows usage above the limit |
| Q-19 | Frontend (Phase 5) | A Plan tab for Owner and Admin (the plan and its state, trial end, features, limits with member usage, "your plan changes are made by our team" with no button); a banner on Members and Ownership while the trial runs (days left) and when it is read-only or locked; in the restricted states the controls the table refuses are shown as not available with the reason (Invite, Reactivate, Change role), never hidden silently; a calm page for non-Owners of a locked company. No new BFF operation (everything new is a server read). Messages come from the stable codes (D-175) |
| Q-20 | Tests beyond the criteria | Real PostgreSQL: the exact end instant (one millisecond before, at, after) for both boundaries; a request after the end with no sweep; the whole gating table, every permission in every state for every role (parity with the spec tables, so a changed row fails); two simultaneous first-company creations by one user (one Trial); a failed creation leaves no grant and no subscription; N simultaneous acceptances and reactivations at the limit; a downgrade over the limit; the plan version rule (a new version does not change existing subscriptions); the sweep racing a request and racing the operator script; the operator script (all guards, a second run, outside a transaction); the migration on existing data; tenant isolation of the plan endpoint; the denial audit |
| Q-21 | Spec amendments | SPEC-PLAN becomes Approved with: the corrections of Q-09 and section 3a (the table made explicit), and new criteria: AC-PLAN-21 (the operator assignment script: ticket, validated transition, audited, idempotent, tested), AC-PLAN-22 (a refused request in a restricted state has the stable code `PLAN_RESTRICTED` and is audited), AC-PLAN-23 (companies that exist when the plans arrive get the state of Q-05), AC-PLAN-24 (the gating table is tested for every permission and state), AC-PLAN-25 (concurrent first-company creations by one user yield exactly one Trial). SPEC-TEN's matrix text gets a pointer to the subscription dimension |
| Q-22 | Slice 007 hand-off | Deletion request and cancellation are allowed in every state (AC-PLAN-04, 13, 20). The purge deletes the subscription and the usage with the company but keeps `user_trial_grant` (no foreign key). A company pending deletion with a locked trial is gated by both rules, the stricter wins. Recorded in the traceability file for slice 007 |
| Q-23 | The entitlement service | One class, `EntitlementService`: `state(companyId)`, `permits(role, permission, companyId)`, `hasFeature(companyId, FeatureKey)`, `limit(companyId, LimitKey)`, `hasRoomFor(companyId, LimitKey)` (members now; teams and assessments later). Feature keys and limit keys are enums matched by CHECK to the tables. Nothing else reads the plan tables |

## 3a. The gating table (proposed, from the SPEC-PLAN state table)
"Role" means the existing matrix still applies; this table only adds the subscription dimension. PENDING_DELETION is a separate dimension applied as before.

| Operation (permission) | TRIALING, ACTIVE | EXPIRED_READ_ONLY, NO_PLAN | EXPIRED_LOCKED |
|---|---|---|---|
| Reads: company details, core records, reports, member list, plan | per matrix | per matrix | **Owner only** |
| Create, edit, delete core records; manage settings | per matrix | rejected | rejected |
| Export data | per matrix and plan feature | rejected | rejected |
| Invite; resend an invitation | per matrix | rejected | rejected |
| Revoke an invitation | per matrix | **allowed** (spec silent; it only withdraws) | rejected |
| Accept an invitation (adds a member) | per limit | rejected | rejected |
| Change role; assign or revoke Admin | per matrix | rejected | rejected |
| Reactivate a member (adds a member) | per matrix and limit | rejected | rejected |
| Suspend or remove a member | per matrix | **allowed** | rejected (Q-09) |
| Leave the company | per matrix | allowed | rejected (Q-09) |
| Transfer ownership: start, accept, cancel | per matrix | allowed | rejected (Q-09) |
| Request or cancel company deletion | per matrix | allowed | allowed (Owner) |
| Manage plans | Owner row, no endpoint | no endpoint | no endpoint |

Spec-silent cells chosen here, for your check: revoking an invitation in the read-only states; suspending (not only removing) a member in the read-only states; invitation acceptance in the expired states.

## 4. Phases
### Phase 0: approvals
This plan approved or amended; D-195 and following recorded in the register (Q-01 to Q-23); D-68 updated (Q-02); SPEC-PLAN and SPEC-TEN amended (Q-21); no code.

### Phase 1: schema
`V15` (Q-03 to Q-05): catalog, seed, subscription, trial grant, backfill. Schema tests with mutation checks on each constraint and trigger; the migration on a database with V1 to V14 data (and on an empty one).

### Phase 2: domain and service
`SubscriptionState`, `SubscriptionTransitions`, the subscription and plan entities, `EntitlementService`, the subscription created in the company-creation transaction, the sweep, `PlanActiveMemberLimit` (Q-06, Q-07, Q-11, Q-23). Unit tests for the pure parts; PostgreSQL tests for the trial grant race, the boundaries, the limit under concurrency and the sweep.

### Phase 3: gating, contract and API
The policy extension and every existing operation through it (Q-08, Q-10, Q-14), the two error codes, `GET .../subscription` (Q-13), OpenAPI, `schema.d.ts`, Postman. HTTP tests across states and roles, including the denial audit.

### Phase 4: operator assignment
The script, its tests with mutation checks, the finished `plan-change.md`, a local `psql` rehearsal by me (Q-15). The staging rehearsal is your team's.

### Phase 5: frontend
Plan tab, banners, restricted states, tests, Playwright (trial running, read-only, locked, active, limit reached), axe, keyboard pass, screenshots at three widths (Q-19).

### Phase 6: review and closure
Read-only security and code reviews, fixes, traceability, the list of unrun checks, closure for your sign-off.

## 5. Expected files (approximate)
- Backend: `V15__create_plans_and_subscriptions.sql`; `plan/**` (domain: states, transitions, keys; persistence; application: `EntitlementService`, sweep, `PlanActiveMemberLimit`; web: controller and DTO); `CompanyPolicy`, `CompanyAuthorizationService`, `CompanyCreator`, `InvitationAcceptTransactions`, `MembershipService` (calls only); `SecurityConfig` (one matcher); error mapping for the two codes; tests under `src/test/.../plan/**`; `UnlimitedActiveMembers` deleted.
- Contract and clients: `contracts/openapi.yaml`, `apps/web/src/lib/api/schema.d.ts`, `lib/api/messages.ts`, Postman collection.
- Frontend: `components/plan/**`, `app/(app)/companies/[companyId]/plan/page.tsx`, banners in Members, Ownership and Invitations, `CompanyHeader` (the tab), tests and e2e specs.
- Operations and docs: `docs/runbooks/operations/sql/operator-assign-plan.sql`, `plan-change.md`, `docs/specs/006-plans-entitlements/traceability.md`, the register, the SPEC-PLAN and SPEC-TEN amendments.

## 6. Acceptance-criteria coverage
| Criteria | Where |
|---|---|
| AC-PLAN-01, 09, 15, 25 | Phases 1 and 2 (trial from the creation instant, same transaction, one per user, the race) |
| AC-PLAN-02, 16 | Phases 1 and 2 (versioned tables, immutable versions, existing subscriptions keep theirs) |
| AC-PLAN-03, 07, 08 | Phase 3 (no write endpoint; tenant isolation; Owner and Admin only) |
| AC-PLAN-04, 10, 12, 13, 18, 19, 20, 24 | Phases 2 and 3 (the pure state function with the boundaries, the whole gating table over HTTP) |
| AC-PLAN-05, 14 | Phase 2 and 3 for the member limit (concurrency on PostgreSQL, downgrade); teams and assessments stay open until the domain spec (Q-12) |
| AC-PLAN-06 | Not applicable: no billing state exists; the only writers are the application and the operator script (stated in the traceability) |
| AC-PLAN-11, 17, 21, 22, 23 | Phases 2, 3 and 4 (idempotent audited transitions, rejected transitions, the script, the stable code, the backfill) |
| AC-MEM-17, AC-INV-12, AC-INV-26 | Phase 2 and 3 (closed here, from the earlier slices' hooks) |
| AC-TEN-12, 14 | Phase 3 (plan change rejected for every role by the absence of a route and the matrix test route; export rejected in the read-only window through the test route) |

## 7. Frontend brief (Phase 5)
- **Plan tab** (Owner and Admin): the plan name and version, the state in words ("Trial, 9 days left", "Trial ended, read-only until 12 Nov", "Locked", "Starter"), the trial end in UTC, the features as a list, the limits as rows (Active members 12 of 15; Teams 3, "not tracked yet"), and one line: "Plan changes are made by our team." No button, no form.
- **Banners** on Members, Ownership and Pending invitations for Owner and Admin: trial days left (calm), read-only ("Your trial has ended. You can still read and manage who has access; changes to data and invitations are paused."), locked.
- **Restricted states:** Invite, Reactivate and Change role are shown as not available with the reason, never silently removed; a `PLAN_RESTRICTED` answer says what is paused and that the Owner can contact support; `MEMBER_LIMIT_REACHED` says the company has reached its member limit.
- **Locked company, non-Owner:** one calm page ("This company's plan has ended. Ask the Owner."), with Leave if Q-09 allows it.
- Not built: pricing, upgrade, payment, a trial extension, emails.

## 8. Risks
- **The gating touches every existing operation.** Mitigated by one pure function in the policy, the whole table tested against the spec for every role, state and permission, and no change to a controller except the error mapping.
- **Time at the boundary.** Mitigated by a single function, the injected clock, tests one millisecond before, at and after both instants, and no decision that depends on a job.
- **The member limit under concurrency.** The company lock already serializes the two operations that add a member; the new tests prove it at the limit with many threads.
- **The backfill.** A wrong choice either locks existing companies (NO_PLAN) or gives free trials; Q-05 asks you. The migration is additive and tested on existing data.
- **A trial grant lost to a purge, or kept.** `user_trial_grant` has no foreign key to the company on purpose (Q-04); slice 007 must not delete it.
- **Spec ambiguities.** Q-09 and the three silent cells of section 3a are decisions, not guesses: nothing is built on them until you answer.
- **The operator script can give a company a plan.** Same safeguards as the other scripts: two people, a ticket, audit, single transaction, a rehearsal before routine use.

## 9. Checks to run
`./mvnw verify` (Testcontainers PostgreSQL), contract lint and the API-type drift check, web unit tests, typecheck, lint, build, Playwright, the CI jobs, `V15` validated on an empty database and on one with V1 to V14 data, the operator script run with real `psql`. Checks not run are listed in the traceability file.

## 10. Approval
1. Approve Q-01 to Q-23 as written, or change any of them. Needing your decision: Q-02 (D-68), Q-05 (existing companies), Q-09 (locked company), Q-12 (teams and assessments). Most worth a look: Q-08 and section 3a (the three silent cells), Q-11 (the new error code).
2. Approve SPEC-PLAN with the amendments of Q-21.
3. Approve the phase order and that each phase is its own PR.
