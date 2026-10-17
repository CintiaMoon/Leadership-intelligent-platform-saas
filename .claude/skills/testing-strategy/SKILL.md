# Testing Strategy

## When to use
Use when creating or reviewing tests for any feature.

## Procedure
1. Map every acceptance criterion to at least one test or document why it is not automatable.
2. Use unit tests for pure business logic.
3. Use real PostgreSQL integration tests for constraints, transactions, and concurrency.
4. Use security tests for authentication, authorization, CSRF, tenant isolation, and token replay.
5. Use E2E tests for critical user journeys.
6. Include negative cases, invalid states, expiry, retries, and partial failures.
7. Make tests deterministic and isolated.
8. Report every command run and every skipped test honestly.

## Forbidden shortcuts
- Do not mock away the database invariant that the test is intended to prove.
- Do not remove assertions to make a test pass.
- Do not treat a unit test as proof of concurrency safety.

## Completion checklist
- Requirement-to-test matrix is updated.
- Negative and boundary cases are covered.
- Database-dependent invariants use PostgreSQL.
- Failed and unrun checks are clearly reported.
