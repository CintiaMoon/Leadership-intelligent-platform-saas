-- SPEC-DEL (slice 007), Phase 1: the company lifecycle, the tombstone and the company of an outbox event.
-- Plan: docs/specs/007-company-deletion/plan.md (Q-03, Q-10, Q-33). Nothing in the application writes the new columns yet:
-- Phase 2 does. Every statement below is metadata-only or touches small tables (company, outbox_event after its
-- retention purge), so the locks are short. The CHECKs are added NOT VALID and validated after, the pattern of V12.

-- 1. The deletion request and the purge state, on the company row.
--    deletion_requested_at / _by / purge_at: set once, when the deletion is requested, cleared only by a cancellation.
--    purge_attempts, purge_next_attempt_at, purge_claimed_until, purge_error_code: the purge worker's bookkeeping.
--    purge_error_code is an enum name, never a message. purge_hold_*: an operator's hold on the purge (Q-33).
ALTER TABLE company
    ADD COLUMN deletion_requested_at timestamptz,
    ADD COLUMN deletion_requested_by uuid,
    ADD COLUMN purge_at              timestamptz,
    ADD COLUMN purge_attempts        integer NOT NULL DEFAULT 0,
    ADD COLUMN purge_next_attempt_at timestamptz,
    ADD COLUMN purge_claimed_until   timestamptz,
    ADD COLUMN purge_error_code      text,
    ADD COLUMN purge_hold_ticket     text,
    ADD COLUMN purge_hold_set_at     timestamptz,
    ADD CONSTRAINT fk_company_deletion_requested_by FOREIGN KEY (deletion_requested_by) REFERENCES app_user (id);

-- A company can only be PENDING_DELETION here if a row was edited by hand: nothing in the application sets that status.
-- Such a row is given the deletion request the new CHECK demands, from data it already has: the request is its last
-- update, the actor is its Owner, the deadline is 30 days later. Production is expected to have none (the count is
-- recorded in the slice traceability when this runs there).
UPDATE company c
   SET deletion_requested_at = c.updated_at,
       purge_at              = c.updated_at + interval '30 days',
       deletion_requested_by = (SELECT m.user_id
                                  FROM membership m
                                 WHERE m.company_id = c.id AND m.role = 'OWNER' AND m.status IN ('ACTIVE', 'SUSPENDED')
                                 ORDER BY m.created_at
                                 LIMIT 1)
 WHERE c.status = 'PENDING_DELETION';

ALTER TABLE company DROP CONSTRAINT ck_company_status;
ALTER TABLE company
    ADD CONSTRAINT ck_company_status CHECK (status IN
        ('ACTIVE', 'PENDING_DELETION', 'PURGE_QUEUED', 'PURGING', 'PURGE_FAILED')) NOT VALID,
    -- What each state needs and forbids. The deletion request is present from PENDING_DELETION on, with a deadline after
    -- it. Only PURGING holds a lease, and PURGE_FAILED has an error code and at least one attempt.
    ADD CONSTRAINT ck_company_lifecycle_fields CHECK (
           (status = 'ACTIVE'
                AND deletion_requested_at IS NULL AND deletion_requested_by IS NULL AND purge_at IS NULL
                AND purge_attempts = 0 AND purge_next_attempt_at IS NULL AND purge_claimed_until IS NULL
                AND purge_error_code IS NULL)
        OR (status = 'PENDING_DELETION'
                AND deletion_requested_at IS NOT NULL AND deletion_requested_by IS NOT NULL
                AND purge_at IS NOT NULL AND purge_at > deletion_requested_at
                AND purge_attempts = 0 AND purge_next_attempt_at IS NULL AND purge_claimed_until IS NULL
                AND purge_error_code IS NULL)
        OR (status = 'PURGE_QUEUED'
                AND deletion_requested_at IS NOT NULL AND deletion_requested_by IS NOT NULL
                AND purge_at IS NOT NULL AND purge_at > deletion_requested_at
                AND purge_claimed_until IS NULL)
        OR (status = 'PURGING'
                AND deletion_requested_at IS NOT NULL AND deletion_requested_by IS NOT NULL
                AND purge_at IS NOT NULL AND purge_at > deletion_requested_at
                AND purge_claimed_until IS NOT NULL)
        OR (status = 'PURGE_FAILED'
                AND deletion_requested_at IS NOT NULL AND deletion_requested_by IS NOT NULL
                AND purge_at IS NOT NULL AND purge_at > deletion_requested_at
                AND purge_claimed_until IS NULL AND purge_error_code IS NOT NULL AND purge_attempts >= 1)) NOT VALID,
    ADD CONSTRAINT ck_company_purge_attempts CHECK (purge_attempts BETWEEN 0 AND 5) NOT VALID,
    ADD CONSTRAINT ck_company_purge_error_code CHECK (purge_error_code IS NULL OR purge_error_code ~ '^[A-Z_]{1,40}$') NOT VALID,
    -- A hold has a ticket and a moment, or neither (letters, digits, - and _, as in the other operator tickets).
    ADD CONSTRAINT ck_company_purge_hold CHECK (
           (purge_hold_ticket IS NULL AND purge_hold_set_at IS NULL)
        OR (purge_hold_ticket IS NOT NULL AND purge_hold_ticket ~ '^[A-Za-z0-9_-]{1,40}$' AND purge_hold_set_at IS NOT NULL)) NOT VALID;
ALTER TABLE company VALIDATE CONSTRAINT ck_company_status;
ALTER TABLE company VALIDATE CONSTRAINT ck_company_lifecycle_fields;
ALTER TABLE company VALIDATE CONSTRAINT ck_company_purge_attempts;
ALTER TABLE company VALIDATE CONSTRAINT ck_company_purge_error_code;
ALTER TABLE company VALIDATE CONSTRAINT ck_company_purge_hold;

-- The worker's three questions: which pending companies are due, which queued or failed ones may be tried again, and
-- which purges lost their lease.
CREATE INDEX ix_company_purge_due ON company (purge_at) WHERE status = 'PENDING_DELETION';
CREATE INDEX ix_company_purge_retry ON company (purge_next_attempt_at) WHERE status IN ('PURGE_QUEUED', 'PURGE_FAILED');
CREATE INDEX ix_company_purge_lease ON company (purge_claimed_until) WHERE status = 'PURGING';

-- The lifecycle of SPEC-DEL: ACTIVE to PENDING_DELETION (a request), PENDING_DELETION to ACTIVE (a cancellation) or to
-- PURGE_QUEUED (the deadline), PURGE_QUEUED to PURGING (a claim), PURGING to PURGE_FAILED, PURGE_FAILED to PURGE_QUEUED
-- (a retry). Nothing else. The request columns never change while they are set, except that a cancellation clears
-- them. The deadline itself is the application's decision, with the injected clock: this trigger does not read the
-- database clock, so tests and the application agree on time.
CREATE FUNCTION assert_company_lifecycle() RETURNS trigger AS $$
BEGIN
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'ACTIVE' AND NEW.status = 'PENDING_DELETION')
        OR (OLD.status = 'PENDING_DELETION' AND NEW.status IN ('ACTIVE', 'PURGE_QUEUED'))
        OR (OLD.status = 'PURGE_QUEUED' AND NEW.status = 'PURGING')
        OR (OLD.status = 'PURGING' AND NEW.status = 'PURGE_FAILED')
        OR (OLD.status = 'PURGE_FAILED' AND NEW.status = 'PURGE_QUEUED')) THEN
        RAISE EXCEPTION 'company lifecycle transition % to % is not allowed', OLD.status, NEW.status;
    END IF;
    IF OLD.deletion_requested_at IS NOT NULL AND NOT (OLD.status = 'PENDING_DELETION' AND NEW.status = 'ACTIVE')
        AND (NEW.deletion_requested_at IS DISTINCT FROM OLD.deletion_requested_at
             OR NEW.deletion_requested_by IS DISTINCT FROM OLD.deletion_requested_by
             OR NEW.purge_at IS DISTINCT FROM OLD.purge_at) THEN
        RAISE EXCEPTION 'the deletion request of a company never changes once it is recorded';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_lifecycle BEFORE UPDATE ON company
    FOR EACH ROW EXECUTE FUNCTION assert_company_lifecycle();

-- 2. The tombstone (D-39): the company id, when the deletion was requested and when the data was purged, and nothing
--    else. No foreign key, so it outlives the company. Insert-only: a row is never changed or removed, not even by
--    TRUNCATE. It may only exist once the company row is gone, which the deferred trigger checks at commit, so a
--    purge that writes the tombstone must delete the company in the same transaction.
CREATE TABLE company_tombstone (
    company_id            uuid        NOT NULL,
    deletion_requested_at timestamptz NOT NULL,
    purged_at             timestamptz NOT NULL,
    CONSTRAINT pk_company_tombstone PRIMARY KEY (company_id),
    CONSTRAINT ck_company_tombstone_order CHECK (purged_at >= deletion_requested_at)
);

CREATE FUNCTION refuse_tombstone_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'company tombstones are insert-only (% on %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_tombstone_insert_only BEFORE UPDATE OR DELETE ON company_tombstone
    FOR EACH ROW EXECUTE FUNCTION refuse_tombstone_change();
CREATE TRIGGER trg_company_tombstone_no_truncate BEFORE TRUNCATE ON company_tombstone
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_tombstone_change();

CREATE FUNCTION assert_tombstoned_company_is_gone() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM company WHERE id = NEW.company_id) THEN
        RAISE EXCEPTION 'company % has a tombstone but still exists', NEW.company_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_company_tombstone_company_gone
    AFTER INSERT ON company_tombstone
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_tombstoned_company_is_gone();

-- 3. The company of an outbox event (Q-10), so a purge can find and delete a company's events. The invitation and
--    ownership-transfer events carry it in their payload as companyId. A trigger copies it on insert, so every writer
--    is covered without a code change; the UPDATE does the same for the rows that exist. Events without a company
--    (the authentication emails) keep NULL and are not touched by a purge. No foreign key: events outlive companies.
ALTER TABLE outbox_event ADD COLUMN company_id uuid;

UPDATE outbox_event
   SET company_id = lower(payload ->> 'companyId')::uuid
 WHERE payload ->> 'companyId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

CREATE INDEX ix_outbox_event_company ON outbox_event (company_id) WHERE company_id IS NOT NULL;

CREATE FUNCTION set_outbox_event_company_id() RETURNS trigger AS $$
BEGIN
    IF NEW.company_id IS NULL
       AND NEW.payload ->> 'companyId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        NEW.company_id := lower(NEW.payload ->> 'companyId')::uuid;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_event_company_id BEFORE INSERT ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION set_outbox_event_company_id();
