# Plans and Entitlements Specification

- ID: SPEC-PLAN
- Status: Draft
- Note: the "Plans and limits", "Billing", "Trial", and "Lifecycle" sections are proposed, pending product-owner approval. Decisions marked "Decided 2026-10-16" were approved by the user from the decision-register suggestions. See `docs/architecture/decision-register.md` (D-33 to D-36, D-45 to D-48).
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

## Plans and limits
Trial: 14 days, full Team feature set, maximum 3 teams, 15 active members, and 3 active assessments.
Starter: maximum 3 teams, 15 active members, and 2 active assessments; core scoring, reports, historical comparisons, and CSV export.
Team: maximum 15 teams, 100 active members, and 10 active assessments; Starter capabilities plus advanced analytics, benchmarking, anomaly detection, and extended historical reporting.
Plan limits and feature entitlements are configurable and must not be hard-coded in controllers.
One company is associated with one subscription.
An active member is a company membership with status ACTIVE; people in the people directory are not counted (decision D-48). Active resource definitions must be explicit and consistent.
The limits on teams, active members, and active assessments are strict quotas enforced atomically under concurrency (D-36, decided 2026-10-16).
Only an Owner or Admin can view the company's subscription and entitlement details; Agent and Viewer cannot (D-33, decided 2026-10-16).

## Billing
Starter and Team are intended to be paid plans.
Billing provider integration is out of scope for the initial MVP.
Subscription state and entitlements must support a future provider integration.
No production payment state may be inferred from frontend input.
In the MVP, plan changes are made by an operator through an audited runbook; there is no self-service or provider-driven plan change (D-35, decided 2026-10-16).
AI credits are out of scope for the MVP (D-46, decided 2026-10-16).

## Trial
Start the trial when the first company is successfully created.
A Trial is granted only for the first company a user creates (D-45, decided 2026-10-16). The starting plan state of a later company created by the same user is not yet defined.
Trial duration is exactly 14 days.
Use server-side UTC time and persist timestamps in PostgreSQL.
At expiry, preserve data and transition the subscription to EXPIRED.
Permit read-only access for 14 additional days; block new quota-consuming operations. Data export is not a read operation and is blocked during this window.
After the read-only window ends, the subscription remains EXPIRED, company data is retained, only an Owner can read it, and the Owner can still request deletion (D-34, decided 2026-10-16).
Trial expiry must be enforced synchronously by backend policy checks, not only by a scheduled job.

## Lifecycle
Paid upgrades take effect after payment/subscription confirmation.
Downgrades take effect at the end of the current paid period.
If usage exceeds the limits of the new plan after a downgrade, new quota-consuming operations are blocked and existing data is retained (D-35, decided 2026-10-16).
Cancellation stops renewal but preserves paid access through the paid-through timestamp.
Failed renewal enters PAST_DUE with a 7-day grace period.
After grace expiry, restrict writes and retain data.
Subscription transitions must be idempotent and auditable.

## Open items
- Define "team", "assessment", and "active resource"; these terms appear in no other document (D-33). "Active member" is decided (D-48).
- Define the starting plan state of a later company created by a user who already received a Trial (D-45).
- Reconcile "read-only access" with "block new quota-consuming operations" for the trial window, and define "restrict writes" after PAST_DUE grace expiry (D-35).
- State where plan configuration lives (database or file) and who may change it.
- State whether expiry is inclusive or exclusive at the exact trial end instant.

## Acceptance criteria
- AC-PLAN-01: Trial expiry is calculated server-side from the approved start event.
- AC-PLAN-02: Starter and Team entitlements come from approved configuration/data.
- AC-PLAN-03: Client-supplied plan and entitlement values are ignored.
- AC-PLAN-04: When a trial ends, the subscription transitions to EXPIRED, company data is preserved, reads remain permitted for the following 14 days, and new quota-consuming operations are rejected during that period.
- AC-PLAN-05: Concurrent requests cannot exceed the limit on teams, active members, or active assessments.
- AC-PLAN-06: Billing state, if present, is updated only from verified provider events or trusted administrative workflows.
- AC-PLAN-07: Plan, subscription, and usage data of one company cannot be read or changed by members of another company.
- AC-PLAN-08: Only an Owner or Admin can read the company's subscription and entitlement details; requests from Agent and Viewer are rejected.
- AC-PLAN-09: The trial start timestamp is persisted as server-side UTC in the same transaction that creates the user's first company, and a failed company creation leaves no subscription.
- AC-PLAN-10: A request made after the trial end instant is evaluated as EXPIRED even if no scheduled job has run.
- AC-PLAN-11: Repeating a subscription transition leaves the state unchanged, and every applied transition writes an audit event.
- AC-PLAN-12: During the 14-day read-only window after trial expiry, a data export request is rejected while reads of company data remain permitted.
- AC-PLAN-13: After the 14-day read-only window, only an Owner can read company data and the Owner can still request deletion; reads by other roles are rejected.
- AC-PLAN-14: After a downgrade to a plan whose limits are below current usage, new quota-consuming operations are rejected and existing data is retained.
- AC-PLAN-15: A user's second and later companies do not receive a Trial.

## Traceability
| Requirement | Acceptance criteria |
|---|---|
| PLAN-001 | AC-PLAN-01, AC-PLAN-09, AC-PLAN-15 |
| PLAN-002 | AC-PLAN-02 |
| PLAN-003 | AC-PLAN-03, AC-PLAN-07, AC-PLAN-08, AC-PLAN-14 |
| PLAN-004 | AC-PLAN-04, AC-PLAN-10, AC-PLAN-12, AC-PLAN-13 |
| PLAN-005 | AC-PLAN-05 |
| Supporting | AC-PLAN-06 (billing integrity, if billing is in scope), AC-PLAN-11 (idempotent, audited transitions) |

## Tests
Test immediately before, at, and after expiry; each plan's entitlement matrix; missing/invalid subscription state; concurrent quota consumption; downgrade over limit; Trial granted once per user; and idempotent provider event handling if billing is included.

## Spring Boot implementation constraints
- Expose this capability through the approved OpenAPI REST contract and implement it in the Spring Boot API; endpoint examples are not approved until added to OpenAPI.
- Enforce authentication, tenant membership, and operation-specific authorization on the backend. A tenant ID supplied by the client is not proof of access.
- Keep controllers thin and put business rules in application/domain services.
- Use explicit transaction boundaries and PostgreSQL-backed concurrency tests for race-prone invariants.
- Use Flyway for schema changes and Testcontainers PostgreSQL for persistence/constraint tests.
- Return DTOs and standardized errors; never expose JPA entities or sensitive internal details.
