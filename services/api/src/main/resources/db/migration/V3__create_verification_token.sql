-- Single-use tokens for email verification and password reset (SPEC-AUTH, D-81).
-- Only the SHA-256 of the token is stored; the raw token exists only in the email sent to the user.
CREATE TABLE verification_token (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL,
    purpose     text        NOT NULL,
    token_hash  text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL,
    CONSTRAINT pk_verification_token PRIMARY KEY (id),
    CONSTRAINT fk_verification_token_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uq_verification_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_verification_token_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_verification_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_verification_token_expiry CHECK (expires_at > created_at)
);

-- Finds a user's outstanding tokens of one purpose, to invalidate them when a new one is issued.
CREATE INDEX ix_verification_token_user_purpose_open
    ON verification_token (user_id, purpose) WHERE consumed_at IS NULL;
