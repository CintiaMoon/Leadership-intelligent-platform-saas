-- Fixed-window counters for authentication rate limits (ADR-004, D-86).
-- key_hash is an HMAC-SHA-256 of the policy name and identifier: raw emails and IP addresses are never stored.
CREATE TABLE rate_limit_counter (
    policy       text        NOT NULL,
    key_hash     text        NOT NULL,
    window_start timestamptz NOT NULL,
    hit_count    integer     NOT NULL,
    expires_at   timestamptz NOT NULL,
    CONSTRAINT pk_rate_limit_counter PRIMARY KEY (policy, key_hash, window_start),
    CONSTRAINT ck_rate_limit_counter_hit_count CHECK (hit_count > 0),
    CONSTRAINT ck_rate_limit_counter_window CHECK (expires_at > window_start)
);

-- Supports the expired-counter sweep.
CREATE INDEX ix_rate_limit_counter_expires_at ON rate_limit_counter (expires_at);
