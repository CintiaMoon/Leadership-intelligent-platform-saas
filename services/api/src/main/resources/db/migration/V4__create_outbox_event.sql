-- Durable work records written in the same transaction as the business change (ADR-003).
-- Workers claim rows with FOR UPDATE SKIP LOCKED, deliver outside any database transaction, then record the outcome.
-- Payloads carry identifiers only, never secrets: tokens are minted at delivery time.
CREATE TABLE outbox_event (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    idempotency_key text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        integer     NOT NULL DEFAULT 0,
    max_attempts    integer     NOT NULL DEFAULT 8,
    next_attempt_at timestamptz NOT NULL,
    locked_until    timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL,
    completed_at    timestamptz,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'FAILED')),
    CONSTRAINT ck_outbox_event_attempts CHECK (attempts >= 0 AND max_attempts >= 1)
);

-- Work still to claim: due pending rows and in-flight rows whose lease may have expired.
CREATE INDEX ix_outbox_event_claimable ON outbox_event (next_attempt_at) WHERE status IN ('PENDING', 'IN_FLIGHT');
-- Operators look for rows that exhausted their retries.
CREATE INDEX ix_outbox_event_failed ON outbox_event (created_at) WHERE status = 'FAILED';
