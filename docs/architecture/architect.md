# Architecture

## Status
Proposed — requires product, architecture, and security review.

## Architectural style
Use a modular monolith for the initial release: one Spring Boot application, one PostgreSQL database, and feature-oriented modules. Keep Next.js as a separate frontend application. Do not introduce microservices until operational and scaling evidence justifies them.

## Stack
- Next.js App Router, TypeScript, Tailwind CSS for the web UI.
- Java 21 and Spring Boot 4.x for the REST API.
- Spring Security for authentication integration, request security, CSRF, and authorization enforcement.
- Spring Session JDBC as the proposed server-side session store; select final session topology in an ADR.
- PostgreSQL as the authoritative relational data store.
- Spring Data JPA and Hibernate for persistence.
- Flyway versioned SQL migrations as the production schema source of truth.
- OpenAPI as the API contract; stable request/response DTOs and standard error format.
- Durable outbox/job mechanism for reliable asynchronous work; choose the implementation in an ADR.
- Shared rate-limit storage for multi-instance deployment.

## Suggested backend package layout
Use feature-oriented packages. Names are illustrative; approve final naming in an ADR.
- `auth.api`, `auth.application`, `auth.domain`, `auth.infrastructure`
- `tenant.*`
- `membership.*`
- `invitation.*`
- `ownership.*`
- `entitlement.*`
- `companylifecycle.*`
- `audit.*`
- `shared.security`, `shared.persistence`, `shared.web`, `shared.outbox`

Keep controllers thin. Controllers translate HTTP requests into application use cases and DTO responses. Domain/application services own business rules. Repositories persist domain state. Infrastructure adapters implement email, rate limits, clocks, and background jobs.

## Frontend/backend boundary
The frontend must not be a second authority for business rules. It can perform helpful client validation, but the API repeats all security and business validation. Keep OpenAPI and TypeScript client types aligned.

Choose one browser-to-API topology before implementation:
1. **BFF**: browser calls same-origin Next.js Route Handlers, which proxy to Spring Boot. Define exactly how the session cookie and CSRF token are forwarded and how trusted proxy headers are handled.
2. **Direct API**: browser calls Spring Boot. Prefer same-origin routing through an ingress/reverse proxy. If cross-origin requests are required, configure exact CORS origins and credential behavior; CORS is not CSRF protection.

Do not let Auth.js and Spring Security independently create authoritative sessions. Spring Boot is the authentication and authorization authority in this edition. If Next.js BFF is chosen, its proxy must not bypass backend authorization.

## Request flow
1. Apply HTTP security and validate the session/CSRF requirements.
2. Resolve authenticated user identity from trusted server-side security context.
3. Resolve requested tenant context and verify active membership and operation-specific permission.
4. Validate request DTOs and business preconditions.
5. Execute application/domain use cases with explicit transaction boundaries.
6. Apply tenant-scoped persistence operations and database constraints.
7. Persist audit/outbox records as appropriate.
8. Return a safe DTO or standardized error response.

## Authentication and sessions
Use Spring Security with a revocable server-side session. Spring Session JDBC is the initial proposal. Do not assume stateless JWTs satisfy the server-side session requirement. Define session expiry, renewal, revocation, logout invalidation, session fixation protection, cookie flags, CSRF token delivery, and verification requirements in the approved authentication spec and ADR.

For cookie-authenticated state-changing requests, enable and test CSRF protection. SameSite cookies are defense in depth, not a replacement for CSRF controls. Cookie flags and domain/path must match the selected deployment topology. Never store raw verification or invitation tokens; persist hashes, expiry, and consumed/revoked state.

## Database and transactions
Use JPA entities and repositories with explicit transaction boundaries. Use Flyway for schema evolution. Set Hibernate schema mode to validation or another explicitly approved non-mutating mode in production; never use automatic schema update in production. Use database uniqueness/foreign-key/check constraints where feasible. For concurrent invariants, use pessimistic locking, optimistic versioning, conditional updates, or another documented strategy and verify it with PostgreSQL integration tests.

Never hold a transaction open while sending email or calling external services. Use a transactional outbox or durable job record for side effects that must not be lost after commit. Consumers must be idempotent.

## Tenant isolation
Tenant identity from a URL or request body is only a selector, not proof of authorization. Every tenant-scoped use case must verify the caller's active membership and permission. Repositories and services must make tenant scope explicit. Add tests proving one tenant cannot read, mutate, invite, export, or delete another tenant's data.

## Email and asynchronous work
Use a replaceable provider adapter. Verification and invitation delivery should be retry-safe. Persist durable work if losing a message or lifecycle job would break correctness. Never log raw tokens or full sensitive URLs. Define retry limits, dead-letter/repair behavior, and monitoring.

## Rate limiting and lockout
Use shared storage for multi-instance deployment. Define policies per endpoint and identity/IP dimensions, handling of store failure, and anti-enumeration behavior. Account lockout must not be an easy denial-of-service vector against a known user.

## Plans and entitlements
Separate plan catalog, tenant subscription/trial state, entitlement evaluation, usage accounting, and any future payment provider. All entitlement decisions are enforced server-side. Trial start/end and timezone semantics must be explicit and tested with an injected clock.

## Company deletion
Model deletion as an explicit state machine: active → pending deletion/grace period → purge in progress → purged/tombstoned, with defined cancellation and retry rules. Jobs must be durable and idempotent. Retain only approved tombstone fields and define retention/legal policy before implementation.

## Observability
Use structured logs and metrics with redaction. Audit security-relevant state transitions. Never log passwords, session cookies, raw verification/invitation tokens, or sensitive company content. Include correlation/request IDs without leaking secrets.

## Testing
- Unit tests for domain rules and pure logic.
- Spring MVC/security tests for endpoint and authorization behavior.
- Testcontainers PostgreSQL integration tests for migrations, constraints, transactions, and persistence.
- Concurrency tests for last-Owner protection, invitation consumption, ownership transfer, and deletion/purge.
- API contract tests and frontend integration/E2E tests for critical user journeys.

## Open architecture decisions — resolve before implementation
- BFF proxy versus direct browser-to-Spring API; cookie and CSRF flow.
- Spring Boot patch release.
- Password policy and password-hashing configuration.
- Email provider and durable delivery/outbox mechanism.
- Shared rate-limit store and outage behavior.
- Session expiry, idle timeout, absolute lifetime, and revocation rules.
- Background job implementation and scheduling/locking approach.
- Trial semantics, plan entitlements, billing/payment scope.
- Deployment platform, same-origin routing, secrets, and database topology.
- Audit retention, tombstone fields, and deletion/privacy policy.
