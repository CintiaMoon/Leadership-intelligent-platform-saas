-- Failed-login state for escalating block windows (SPEC-AUTH, D-51, D-78, ADR-004).
-- Two kinds of rows share the table: one per (account, client address) pair and one per account alone.
-- key_hash is an HMAC-SHA-256 of the kind and normalized identifiers, so raw emails and addresses are never stored.
CREATE TABLE login_throttle (
    key_hash        text        NOT NULL,
    failures        integer     NOT NULL,
    window_start    timestamptz NOT NULL,
    last_failure_at timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    CONSTRAINT pk_login_throttle PRIMARY KEY (key_hash),
    CONSTRAINT ck_login_throttle_failures CHECK (failures > 0),
    CONSTRAINT ck_login_throttle_window CHECK (expires_at > window_start)
);

CREATE INDEX ix_login_throttle_expires_at ON login_throttle (expires_at);
