# Plans and Entitlements Specification

- ID: SPEC-PLAN
- Status: Draft
- Note: the "Plans and limits", "Billing", "Trial", and "Lifecycle" sections are proposed, pending product-owner approval. Decisions marked "Decided 2026-10-16" or "Decided 2026-10-17" were approved by the user from decision-register suggestions. See `docs/architecture/decision-register.md` (D-33 to D-36, D-45 to D-48, D-65 to D-68).
- Requirements: PLAN-001 through PLAN-005

## Objective
Provide Trial, Starter, and Team plans with explicit, server-enforced entitlements.

## Known requirement
Trial duration is 14 days. The start event and time policy are stated under "Trial" below and are proposed, pending product-owner approval.

## Recommended conceptual separation
- Plan catalog: plan definitions and included features.
- Company subscription state: currently applicable plan and lifecycle.
- Entitlement evaluator: server-side decision for a specific action.
- Usage accounting: current usage and quota enforcement.
- Billing integration: provider events and payment state, if in scope.

## Terms (proposed from the wireframes; to be confirmed in the domain spec, D-33)
- Team: an organizational unit within a company that groups people for assessment scope.
- Assessment: a questionnaire run launched to teams or cohorts.
- Active assessment: an assessment that has been launched and not closed.
- Active member: a company membership with status ACTIVE; people in the people directory are not counted (D-48).
- Active resource: any countable entity that a plan limits: an active member, a team, or an active assessment.

## Plans and limits
Trial: 14 days, full Team feature set, maximum 3 teams, 15 active members, and 3 active assessments.
Starter: maximum 3 teams, 15 active members, and 2 active assessments; core scoring, reports, historical comparisons, and CSV export.
Team: maximum 15 teams, 100 active members, and 10 active assessments; Starter capabilities plus advanced analytics, benchmarking, anomaly detection, and extended historical reporting.
One company is associated with one subscription.
The limits on teams, active members, and active assessments are strict quotas enforced atomically under concurrency (D-36). The limits on teams and active assessments are deferred until the domain spec defines those entities (D-68); the member limit applies from the first slice.
Only an Owner or Admin can view the company's subscription and entitlement details; Agent and Viewer cannot (D-33).

## Plan configuration (decided 2026-10-17)
- Plans, limits, and features are stored in versioned database tables seeded by Flyway migrations. They are not hard-coded in controllers.
- A change to a plan creates a new plan version. Existing subscriptions keep the version they were assigned.
- Changes go through reviewed migrations and the operator runbook. There is no admin interface in the MVP.
- Entitlements are exposed through one entitlement service, with feature keys (core scoring, reports, historical comparisons, CSV export, advanced analytics, benchmarking, anomaly detection, extended historical reporting) and limit keys (teams, active members, active assessments).

## Billing
Starter and Team are intended to be paid plans.
Billing provider integration is out of scope for the initial MVP.
Subscription state and entitlements must support a future provider integration.
No production payment state may be inferred from frontend input.
In the MVP, plan changes are made by an operator through an audited runbook; there is no self-service or provider-driven plan change (D-35).
AI credits are out of scope for the MVP (D-46).

## Trial
Start the trial when the first company is successfully created.
A Trial is granted only for the first company a user creates (D-45). A later company created by the same user starts in the NO_PLAN state.
Trial duration is exactly 14 days.
Use server-side UTC time and persist timestamps in PostgreSQL.
Expiry uses a half-open interval: the trial is valid while the evaluation time is earlier than the trial end instant, and expired at and after it (decided 2026-10-17).
At expiry, preserve data and transition the subscription to EXPIRED_READ_ONLY.
Trial expiry must be enforced synchronously by backend policy checks, not only by a scheduled job.

## Subscription states (MVP, decided 2026-10-17)
| State | Meaning | Allowed |
|---|---|---|
| TRIALING | Within the 14-day trial | Everything the Trial plan permits |
| EXPIRED_READ_ONLY | The 14 days after the trial ends | Reads by role; member removals, leaving, ownership transfer, and deletion request or cancellation. Business-record writes, quota-consuming operations, invitations, and data export are rejected. |
| EXPIRED_LOCKED | After the read-only window | Reads by Owner only; deletion request or cancellation. Everything else is rejected. |
| ACTIVE | A paid plan (Starter or Team) assigned by an operator | Everything the plan permits |
| NO_PLAN | A later company of a user who already had a Trial | Same as EXPIRED_READ_ONLY until an operator assigns a plan |

Reserved for the billing integration, not implemented in the MVP: PAST_DUE and CANCELED.

Transitions: TRIALING to EXPIRED_READ_ONLY at the trial end instant; EXPIRED_READ_ONLY to EXPIRED_LOCKED 14 days later; TRIALING, EXPIRED_READ_ONLY, EXPIRED_LOCKED, or NO_PLAN to ACTIVE by operator plan assignment; ACTIVE to ACTIVE by operator plan change. No other transition is permitted.

## Lifecycle
MVP: an operator-applied plan change takes effect immediately. If usage exceeds the limits of the new plan after a downgrade, new quota-consuming operations are blocked and existing data is retained (D-35).
Reserved for the billing integration: paid upgrades take effect after payment confirmation; downgrades take effect at the end of the current paid period; cancellation stops renewal but preserves paid access through the paid-through timestamp; failed renewal enters PAST_DUE with a 7-day grace period; after grace expiry the company behaves as in EXPIRED_READ_ONLY (reads allowed, business writes blocked).
Subscription transitions must be idempotent and auditable.

## Open items
- Confirm the proposed terms above and write the domain spec for questionnaires, assessment runs, teams, the people directory, and reports (D-68). Until then the team and assessment limits are not enforceable.
- The operator runbook for plan assignment and changes.

## Acceptance criteria
- AC-PLAN-01: Trial expiry is calculated server-side from the approved start event.
- AC-PLAN-02: Starter and Team entitlements come from approved configuration/data.
- AC-PLAN-03: Client-supplied plan and entitlement values are ignored.
- AC-PLAN-04: When a trial ends, the subscription transitions to EXPIRED_READ_ONLY, company data is preserved, reads remain permitted for the following 14 days, and business-record writes and quota-consuming operations are rejected during that period.
- AC-PLAN-05: Concurrent requests cannot exceed the limit on active members. The same applies to teams and active assessments once the domain spec defines them.
- AC-PLAN-06: Billing state, if present, is updated only from verified provider events or trusted administrative workflows.
- AC-PLAN-07: Plan, subscription, and usage data of one company cannot be read or changed by members of another company.
- AC-PLAN-08: Only an Owner or Admin can read the company's subscription and entitlement details; requests from Agent and Viewer are rejected.
- AC-PLAN-09: The trial start timestamp is persisted as server-side UTC in the same transaction that creates the user's first company, and a failed company creation leaves no subscription.
- AC-PLAN-10: A request made after the trial end instant is evaluated as expired even if no scheduled job has run.
- AC-PLAN-11: Repeating a subscription transition leaves the state unchanged, and every applied transition writes an audit event.
- AC-PLAN-12: During the 14-day read-only window after trial expiry, a data export request is rejected while reads of company data remain permitted.
- AC-PLAN-13: After the 14-day read-only window, only an Owner can read company data and the Owner can still request deletion; reads by other roles are rejected.
- AC-PLAN-14: After a downgrade to a plan whose limits are below current usage, new quota-consuming operations are rejected and existing data is retained.
- AC-PLAN-15: A user's second and later companies do not receive a Trial.
- AC-PLAN-16: Plan limits and features are read from versioned plan tables; changing a plan creates a new version, and existing subscriptions keep the version they were assigned.
- AC-PLAN-17: A subscription state transition that is not in the defined transition list is rejected.
- AC-PLAN-18: A trial is expired when the evaluation time is greater than or equal to the trial end instant.
- AC-PLAN-19: A company in the NO_PLAN state can be read according to roles, and its business-record writes, quota-consuming operations, invitations, and exports are rejected.
- AC-PLAN-20: In EXPIRED_READ_ONLY, business-record writes, invitations, and exports are rejected, while member removals, leaving, ownership transfer, and deletion requests remain allowed.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| PLAN-001 | AC-PLAN-01, AC-PLAN-09, AC-PLAN-15, AC-PLAN-19 |
| PLAN-002 | AC-PLAN-02, AC-PLAN-16 |
| PLAN-003 | AC-PLAN-03, AC-PLAN-07, AC-PLAN-08, AC-PLAN-14 |
| PLAN-004 | AC-PLAN-04, AC-PLAN-10, AC-PLAN-12, AC-PLAN-13, AC-PLAN-18, AC-PLAN-20 |
| PLAN-005 | AC-PLAN-05 |
| Supporting | AC-PLAN-06 (billing integrity, if billing is in scope), AC-PLAN-11 (idempotent, audited transitions), AC-PLAN-17 (state transitions) |

## Tests
Test immediately before, at, and after expiry (including the exact end instant); each plan's entitlement matrix; missing/invalid subscription state; every state and transition in the table; concurrent quota consumption; downgrade over limit; Trial granted once per user; plan version changes; and idempotent provider event handling once billing is included.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
