# Project Instructions

## Product
A secure, multi-tenant SaaS application with a Next.js web client and a Spring Boot REST API.

## Stack
- Frontend: Next.js App Router, TypeScript, Tailwind CSS
- Backend: Java 21, Spring Boot 4.x; selected  compatible supported patch release: 4.1.1
- Security: Spring Security; proposed server-side sessions via Spring Session JDBC
- Persistence: PostgreSQL, Spring Data JPA, Hibernate
- Migrations: Flyway versioned SQL; do not use Hibernate schema auto-update in production
- API contract: OpenAPI, REST/JSON, stable DTOs and consistent error responses
- Testing: JUnit 5, Spring Boot Test, Spring Security Test, Testcontainers PostgreSQL; frontend unit/E2E tooling chosen in an ADR
- Build: use Maven

## Engineering principles
1. Approved specifications and ADRs are the source of truth.
2. Do not invent business requirements or silently change them.
3. Do not implement a feature before its spec and implementation plan are approved.
4. Work on one approved vertical slice at a time.
5. Keep controllers thin; put business decisions in application/domain services.
6. Validate every external input at the server boundary and validate domain invariants in domain/application logic.
7. Spring Security is the backend authentication/authorization enforcement point. UI visibility is never authorization.
8. Centralize authorization policy and tenant scoping. Never trust a client-supplied tenant ID without checking membership and permission.
9. Enforce critical invariants with database constraints and transactional/locking strategies where feasible.
10. Use explicit `@Transactional` boundaries for multi-record operations; do not perform network calls or send email inside database transactions.
11. Use DTOs for API requests/responses; never serialize JPA entities directly.
12. Flyway migrations are the production schema source of truth. Review SQL, locking, backfills, and destructive changes.
13. Never trust client-provided user IDs, tenant IDs, roles, plan names, or entitlement decisions.
14. Never log passwords, session cookies, raw verification tokens, or raw invitation tokens.
15. Use real PostgreSQL integration tests for constraints, transactions, locking, and concurrency.
16. Never claim a check passed unless it was actually executed.
17. Keep OpenAPI, backend DTOs, frontend API clients, and acceptance criteria aligned.

## Required workflow
For every feature:
1. Read the approved spec, relevant ADRs, architecture invariants, and API contract guidance. Check `docs/architecture/decision-register.md`; do not implement a slice while any decision marked as blocking it is Open.
2. Identify assumptions, unresolved decisions, and trust boundaries.
3. Propose a minimal plan with expected files, migration, API changes, and tests.
4. Obtain approval before implementation.
5. Implement backend, database migration, frontend, and contract updates as applicable.
6. Write unit, integration, authorization, tenant-isolation, concurrency, and E2E tests as appropriate.
7. Run backend tests/build, frontend tests/typecheck/lint/build, and contract validation relevant to the change.
8. Perform independent code and security review.
9. Update traceability, status, and runbooks; document checks not run.

## Completion criteria
- Acceptance criteria pass and are mapped to tests.
- Authentication, authorization, and tenant isolation are tested.
- Relevant concurrency and failure cases are tested against PostgreSQL.
- Flyway migrations are reviewed and validated on a clean database.
- API contract changes are compatible or explicitly versioned.
- No unrelated regressions are introduced.
- Deviations and unrun checks are documented.

## Safety
- Never run destructive commands against production without explicit authorization and a reviewed runbook.
- Never expose or commit secrets.
- Do not disable security controls to make tests pass.
- Ask before changing approved requirements or architecture.
- Use a dedicated test database and isolated test credentials.
- Review migration SQL before applying important or destructive changes.
- Do not run multiple agents concurrently against the same migration, entity mapping, security configuration, or API contract without explicit ownership coordination.
