-- Memberships join a user to a company with a role and a status (SPEC-TEN, D-23, D-43, D-54, D-112).
-- A removed member who is invited again gets a new row, so history stays.
-- Foreign keys are RESTRICT: slice 007's purge must delete memberships and the company in one transaction.
CREATE TABLE membership (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    company_id uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_membership PRIMARY KEY (id),
    CONSTRAINT fk_membership_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_membership_role CHECK (role IN ('OWNER', 'ADMIN', 'AGENT', 'VIEWER')),
    CONSTRAINT ck_membership_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED'))
);

-- One live membership per user and company.
CREATE UNIQUE INDEX uq_membership_live_member ON membership (company_id, user_id)
    WHERE status IN ('ACTIVE', 'SUSPENDED');

-- At most one Owner per company. A SUSPENDED Owner still holds the role (D-54).
CREATE UNIQUE INDEX uq_membership_single_owner ON membership (company_id)
    WHERE role = 'OWNER' AND status IN ('ACTIVE', 'SUSPENDED');

-- Serves the "my companies" query and the per-request membership lookup.
CREATE INDEX ix_membership_user_id ON membership (user_id, status);

-- At commit, every company has exactly one Owner membership (D-112; AC-TEN-01, AC-TEN-02).
-- "At most one" comes from the index above; this trigger supplies "at least one".
CREATE FUNCTION assert_company_has_owner() RETURNS trigger AS $$
DECLARE
    target uuid;
BEGIN
    IF TG_TABLE_NAME = 'company' THEN
        target := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        target := OLD.company_id;
    ELSE
        target := NEW.company_id;
    END IF;
    IF EXISTS (SELECT 1 FROM company WHERE id = target)
       AND NOT EXISTS (SELECT 1 FROM membership
                       WHERE company_id = target AND role = 'OWNER' AND status IN ('ACTIVE', 'SUSPENDED')) THEN
        RAISE EXCEPTION 'company % has no Owner membership', target
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_company_requires_owner
    AFTER INSERT ON company
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_company_has_owner();

CREATE CONSTRAINT TRIGGER trg_membership_keeps_owner
    AFTER INSERT OR UPDATE OR DELETE ON membership
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_company_has_owner();
