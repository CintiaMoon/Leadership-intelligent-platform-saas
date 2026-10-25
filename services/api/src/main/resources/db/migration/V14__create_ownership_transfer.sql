-- Ownership transfers (SPEC-OWN; D-176 to D-194, D-32, D-43). New table only: no lock on an existing one, nothing to
-- backfill. Ownership itself still lives in membership (V10: one Owner per company, enforced by a partial unique index and
-- a deferred trigger); this table records the pending, completed, cancelled and expired requests to change it.
CREATE TABLE ownership_transfer (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id   uuid        NOT NULL,
    initiated_by uuid        NOT NULL,
    recipient_id uuid        NOT NULL,
    status       text        NOT NULL DEFAULT 'PENDING',
    resolution   text,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    resolved_at  timestamptz,
    resolved_by  uuid,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_ownership_transfer PRIMARY KEY (id),
    CONSTRAINT fk_ownership_transfer_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_ownership_transfer_initiated_by FOREIGN KEY (initiated_by) REFERENCES app_user (id),
    CONSTRAINT fk_ownership_transfer_recipient FOREIGN KEY (recipient_id) REFERENCES app_user (id),
    CONSTRAINT fk_ownership_transfer_resolved_by FOREIGN KEY (resolved_by) REFERENCES app_user (id),
    CONSTRAINT ck_ownership_transfer_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    -- Ownership is never handed to oneself.
    CONSTRAINT ck_ownership_transfer_parties CHECK (initiated_by <> recipient_id),
    -- Expires 7 days after it was initiated (D-30); the application sets the value, the database only requires order.
    CONSTRAINT ck_ownership_transfer_expiry CHECK (expires_at > created_at),
    -- A transfer is resolved if and only if it is not PENDING, and the way it ended matches its status.
    CONSTRAINT ck_ownership_transfer_resolution CHECK (
           (status = 'PENDING'   AND resolution IS NULL AND resolved_at IS NULL AND resolved_by IS NULL)
        OR (status = 'COMPLETED' AND resolution = 'accepted' AND resolved_at IS NOT NULL)
        OR (status = 'CANCELLED' AND resolved_at IS NOT NULL AND resolution IN
               ('cancelled_by_initiator', 'cancelled_company_deletion', 'cancelled_operator_transfer'))
        OR (status = 'EXPIRED'   AND resolution = 'expired' AND resolved_at IS NOT NULL)),
    -- Who resolved it: the recipient accepts, the initiator cancels, nobody (the system or the operator) expires it or
    -- cancels it for the operator transfer. A cancellation for a deletion request is by whoever requested the deletion.
    CONSTRAINT ck_ownership_transfer_resolved_by CHECK (
           (resolution = 'accepted'               AND resolved_by = recipient_id)
        OR (resolution = 'cancelled_by_initiator' AND resolved_by = initiated_by)
        OR (resolution IN ('expired', 'cancelled_operator_transfer') AND resolved_by IS NULL)
        OR  resolution = 'cancelled_company_deletion'
        OR  resolution IS NULL)
);

-- At most one pending transfer per company (AC-OWN-10), including under concurrent requests (D-32).
CREATE UNIQUE INDEX uq_ownership_transfer_pending ON ownership_transfer (company_id) WHERE status = 'PENDING';

-- The history of a company, newest first.
CREATE INDEX ix_ownership_transfer_company ON ownership_transfer (company_id, created_at DESC);

-- What is waiting for a person to accept.
CREATE INDEX ix_ownership_transfer_recipient_pending ON ownership_transfer (recipient_id) WHERE status = 'PENDING';

-- The sweep that marks overdue pending transfers EXPIRED (D-64, D-184).
CREATE INDEX ix_ownership_transfer_overdue ON ownership_transfer (expires_at) WHERE status = 'PENDING';
