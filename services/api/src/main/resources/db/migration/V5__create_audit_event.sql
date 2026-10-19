-- Append-only audit trail (docs/architecture/audit-events.md, D-74).
-- Never stores passwords, tokens, cookies, or free text. UPDATE is blocked by a trigger as defense in depth;
-- restricting DELETE and TRUNCATE to a privileged role is enforced with database roles in the deployment slice.
CREATE TABLE audit_event (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    occurred_at    timestamptz NOT NULL,
    event_type     text        NOT NULL,
    company_id     uuid,
    actor_type     text        NOT NULL,
    actor_id       uuid,
    target_type    text        NOT NULL,
    target_id      uuid,
    outcome        text        NOT NULL,
    correlation_id text,
    reason_code    text,
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT ck_audit_event_actor_type CHECK (actor_type IN ('user', 'operator', 'system')),
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('success', 'denied')),
    CONSTRAINT ck_audit_event_type_format CHECK (event_type ~ '^[a-z_]+(\.[a-z_]+)+$')
);

CREATE INDEX ix_audit_event_occurred_at ON audit_event (occurred_at);
CREATE INDEX ix_audit_event_target ON audit_event (target_type, target_id, occurred_at);

CREATE FUNCTION audit_event_block_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event rows are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_block_update
    BEFORE UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_block_update();
