# Flyway Migrations

## When to use
Use when changing PostgreSQL tables, constraints, indexes, data migrations, or persistence behavior in the Spring Boot application.

## Procedure
1. Read the approved spec, data-model document, and relevant ADRs.
2. Identify compatibility, deployment-order, locking, backfill, and rollback/forward-repair concerns.
3. Add the smallest versioned SQL migration that supports approved behavior.
4. Review SQL manually; do not assume a generated migration is safe.
5. Validate the migration on a clean PostgreSQL database and, where relevant, a previous-schema fixture.
6. Test constraints, transaction behavior, and concurrency against real PostgreSQL via Testcontainers.
7. Update JPA mappings to match the migrated schema; keep Hibernate from mutating production schema.
8. Document deployment order, data backfill, monitoring, and recovery steps.
9. Never apply migrations to production outside the approved release process.

## Checklist
- Foreign keys, uniqueness, check constraints, and indexes are correct.
- Tenant ownership is representable and validated.
- Race-prone invariants have an explicit database/locking strategy.
- Migrations are compatible with the deployment/rollback strategy.
- Destructive changes and backfills are reviewed.
- Flyway validation and application pass in CI.
