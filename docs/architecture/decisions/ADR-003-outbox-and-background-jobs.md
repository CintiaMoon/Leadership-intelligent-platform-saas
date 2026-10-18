# ADR-003: Outbox and Background Jobs

- Status: Accepted (suggestion approved by the user on 2026-10-16)
- Date: 2026-10-16
- Decision owners: Architect (roles, see the decision register)
- Related: decision register D-05; SPEC-AUTH, SPEC-INV, SPEC-OWN, SPEC-DEL

## Context
Verification and invitation email, ownership-transfer notifications, trial and subscription transitions, and the purge job need durable, retry-safe asynchronous work. `CLAUDE.md` forbids network calls and email inside database transactions.

## Decision
- A PostgreSQL outbox table holds durable work records (event type, safe payload, status, attempts, schedule, idempotency key).
- Workers claim rows with `SELECT … FOR UPDATE SKIP LOCKED`, so several application instances can run without duplicating work.
- Consumers are idempotent. Retries have a limit, and exhausted records are marked failed and visible for repair.
- Records are written in the same transaction as the business change; delivery happens after commit.

## Alternatives considered
Spring Scheduler with ShedLock; JobRunr or Quartz. They add infrastructure or dependencies that this scale does not yet need.

## Consequences
- No new infrastructure beyond PostgreSQL.
- The outbox table and its indexes need a Flyway migration and Testcontainers tests for claiming, retries, and concurrent workers.
- Retry limits, repair behavior, and monitoring still need to be defined.

## Verification
- Concurrent workers never process the same record twice.
- A crash after commit still delivers the message.
- Failed deliveries are retried up to the limit and then reported.
