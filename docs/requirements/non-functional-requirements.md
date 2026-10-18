# Non-Functional Requirements

## Status
Draft — set measurable targets before production.

## Security
- NFR-SEC-001: All sensitive operations perform server-side authentication and authorization.
- NFR-SEC-002: Tenant isolation is enforced in data-access operations and tested with multiple tenants.
- NFR-SEC-003: Passwords and bearer tokens are never logged or stored in plaintext.
- NFR-SEC-004: Rate limiting works across application instances.
- NFR-SEC-005: Secrets are managed outside source control.
- NFR-SEC-006: Security-relevant events are auditable without recording sensitive token values.

## Reliability
- NFR-REL-001: Background jobs are retry-safe and idempotent where possible.
- NFR-REL-002: Critical state changes have defined transaction boundaries.
- NFR-REL-003: Backup and restoration procedures are documented and tested.
- NFR-REL-004: The availability target is 99%, with business-hours operational support (decision D-08, 2026-10-17). Recovery targets are an RPO of 15 minutes and an RTO of 4 hours; the RTO is a recovery-capability target and does not promise an out-of-hours human response (see ADR-007).

## Performance and capacity
- NFR-PERF-001: Starting baseline, approved by the product owner on 2026-10-18 as a proposal to be validated, not a commitment: 100 companies, 25 concurrent users, and 10 requests per second at peak; p95 latency under 500 ms for reads and under 1 s for writes, measured at the BFF, excluding password hashing (Argon2id is slow by design). The release-gate load test (ADR-004) confirms or revises these numbers.
- NFR-PERF-002: Rate-limit and session storage must support the intended deployment topology.

## Privacy and retention
- NFR-PRIV-001: Personal and company data retention periods are documented.
- NFR-PRIV-002: Tombstones retain only approved minimum metadata.
- NFR-PRIV-003: Backup retention and deletion limitations are documented accurately.

## Accessibility and usability
- NFR-UX-001: Critical flows support keyboard navigation and accessible labels.
- NFR-UX-002: Forms communicate validation errors and safe recovery actions.
- NFR-UX-003: Destructive actions clearly explain consequences and require deliberate confirmation.

## Operations
- NFR-OPS-001: Migrations are reviewed and applied through controlled deployment steps.
- NFR-OPS-002: Logs, metrics, and alerts do not expose secrets or unnecessary personal data.
- NFR-OPS-003: Production recovery and incident procedures are documented.
- NFR-OPS-004: Monitoring uses CloudWatch alarms for the 5xx rate, load-balancer latency, database CPU, storage and connections, outbox backlog, rate-limit store errors, and the billing budget (80%). Alarms notify by email; there is no 24x7 on-call (ADR-007). Logs are structured JSON with a correlation ID and no secrets (NFR-OPS-002).
- NFR-OPS-005: Incidents use the severity scheme and business-hours response targets in `docs/runbooks/operations/incident-response.md`. The targets are proposed values.


## Implementation alignment
The API is implemented as a Spring Boot modular monolith with PostgreSQL. Security, tenant isolation, consistency, observability, and performance requirements apply regardless of frontend behavior. Exact service-level objectives and load targets remain product/architecture decisions until approved.
