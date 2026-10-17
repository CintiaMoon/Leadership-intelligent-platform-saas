# Feature Workflow Runbook

## Before implementation
- [ ] Read `CLAUDE.md`, approved spec, invariants, threat model, and relevant ADRs.
- [ ] Confirm acceptance-criteria IDs and open decisions.
- [ ] Agree ownership of files among agents; avoid concurrent edits to the same migration, entity, security configuration, or OpenAPI contract.
- [ ] List expected backend, frontend, migration, contract, and test changes.
- [ ] Update/approve OpenAPI contract where the feature crosses the API boundary.
- [ ] Obtain approval for the implementation plan.

## During implementation
- [ ] Implement one vertical slice.
- [ ] Keep controllers thin and business rules in application/domain services.
- [ ] Validate input at the API boundary and enforce domain invariants.
- [ ] Enforce authentication, authorization, and tenant scoping on every relevant use case.
- [ ] Use explicit transaction and idempotency strategies.
- [ ] Keep network calls/email outside database transactions; use outbox/jobs when durability matters.
- [ ] Add tests for success, failure, security, tenant isolation, and concurrency.
- [ ] Add Flyway migration only when needed and review its SQL.

## Before completion
- [ ] Validate migration on an empty PostgreSQL database and relevant prior-schema fixture.
- [ ] Run backend unit and integration tests, including Testcontainers tests where required.
- [ ] Run Spring Security tests and relevant E2E tests.
- [ ] Run OpenAPI validation/contract tests.
- [ ] Run frontend tests, typecheck, lint, and production build if frontend code changed.
- [ ] Run backend build and static checks.
- [ ] Obtain independent code/security review.
- [ ] Fix findings and rerun affected checks.
- [ ] Update traceability and implementation status.
- [ ] Document deviations, operational concerns, and checks not run.
- [ ] Commit only after review.
