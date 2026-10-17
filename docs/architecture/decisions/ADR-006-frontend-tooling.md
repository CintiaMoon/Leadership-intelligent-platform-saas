# ADR-006: Frontend Test Tooling

- Status: Accepted (suggestion approved by the user on 2026-10-16)
- Date: 2026-10-16
- Decision owners: TBD
- Related: decision register D-07

## Context
`CLAUDE.md` leaves frontend unit and end-to-end tooling to an ADR, and E2E tests are acceptance gates.

## Decision
- Unit and component tests: Vitest.
- End-to-end tests: Playwright, run through the approved BFF topology (ADR-001).

## Alternatives considered
Jest for unit tests; Cypress for end-to-end tests.

## Consequences
- Frontend slices add Vitest tests and Playwright journeys alongside the TypeScript typecheck, lint, and build.
- Playwright tests need a test environment with the Spring Boot API and PostgreSQL.

## Verification
- Frontend checks (typecheck, lint, unit tests, build) and at least one E2E journey run in CI before a frontend slice is closed.
