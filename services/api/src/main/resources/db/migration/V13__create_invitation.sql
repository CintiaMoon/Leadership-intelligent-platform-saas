-- Invitations to join a company (SPEC-INV; D-24, D-25, D-146, D-147). New table only: no lock on an existing one.
-- The raw token is never stored. token_hash is NULL until the invitation email is delivered (the delivery step mints
-- the token), and again after create, resend, accept, revoke or expiry, so a token that has been replaced or used
-- cannot be looked up at all (AC-INV-05, 15, 19, 21).
CREATE TABLE invitation (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id       uuid        NOT NULL,
    email_normalized text        NOT NULL,
    email_original   text        NOT NULL,
    role             text        NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING',
    token_hash       text,
    invited_by       uuid        NOT NULL,
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    accepted_at      timestamptz,
    accepted_by      uuid,
    revoked_at       timestamptz,
    revoked_by       uuid,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_invitation PRIMARY KEY (id),
    CONSTRAINT fk_invitation_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_invitation_invited_by FOREIGN KEY (invited_by) REFERENCES app_user (id),
    CONSTRAINT fk_invitation_accepted_by FOREIGN KEY (accepted_by) REFERENCES app_user (id),
    CONSTRAINT fk_invitation_revoked_by FOREIGN KEY (revoked_by) REFERENCES app_user (id),
    -- Nobody is invited as Owner (D-27); ownership moves only by transfer.
    CONSTRAINT ck_invitation_role CHECK (role IN ('ADMIN', 'AGENT', 'VIEWER')),
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_invitation_email_lowercase CHECK (email_normalized = lower(email_normalized)),
    CONSTRAINT ck_invitation_email_length CHECK (char_length(email_normalized) BETWEEN 3 AND 254),
    CONSTRAINT ck_invitation_token_hash CHECK (token_hash IS NULL OR token_hash ~ '^[0-9a-f]{64}$'),
    -- Only a pending invitation can hold a token.
    CONSTRAINT ck_invitation_token_only_when_pending CHECK (status = 'PENDING' OR token_hash IS NULL),
    CONSTRAINT ck_invitation_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_invitation_accepted CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL AND accepted_by IS NOT NULL)),
    CONSTRAINT ck_invitation_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- A token identifies at most one invitation. Lookup by token is by this hash only.
CREATE UNIQUE INDEX uq_invitation_token_hash ON invitation (token_hash) WHERE token_hash IS NOT NULL;

-- At most one pending invitation per company and address (D-25, AC-INV-15, AC-INV-28), including under concurrency.
CREATE UNIQUE INDEX uq_invitation_pending ON invitation (company_id, email_normalized) WHERE status = 'PENDING';

-- The pending list of a company, and the revocation of an inviter's invitations when their membership changes (D-62).
CREATE INDEX ix_invitation_company_status ON invitation (company_id, status);

CREATE INDEX ix_invitation_pending_inviter ON invitation (company_id, invited_by) WHERE status = 'PENDING';
