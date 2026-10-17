# Spring Boot Feature Implementation

## When to use
Use for implementing an approved backend feature in Java 21 and Spring Boot.

## Procedure
1. Read `CLAUDE.md`, approved spec, architecture invariants, ADRs, and OpenAPI contract.
2. Identify endpoint, authorization policy, domain rules, transaction boundary, persistence changes, side effects, and tests.
3. Keep controller methods thin: parse HTTP input, invoke use case, return DTO/status.
4. Put business rules in application/domain services, not controllers or JPA entity callbacks.
5. Use Jakarta Bean Validation for request DTO shape and explicit domain validation for business invariants.
6. Use Spring Security for authenticated identity and access enforcement; do not accept user identity/roles from request payloads.
7. Scope tenant-owned operations by verified membership and permission.
8. Use explicit `@Transactional` boundaries; do not call email/network services inside a database transaction.
9. Use repositories and DTO mapping; do not expose JPA entities or trigger uncontrolled lazy loading during serialization.
10. Add unit tests, Spring integration/security tests, and PostgreSQL Testcontainers tests for persistence/transactions.
11. Run the build and report exact commands and outcomes. Do not claim unrun checks passed.

## Review checklist
- Acceptance criteria map to tests.
- Error responses are safe and consistent.
- Authorization is checked for every operation.
- Transaction and idempotency semantics are explicit.
- No secrets are logged.
- API contract is updated when behavior changes.
