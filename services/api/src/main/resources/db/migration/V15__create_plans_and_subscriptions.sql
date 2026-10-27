-- Plans, subscriptions and the one-Trial-per-user grant (SPEC-PLAN; D-195 to D-217, D-33 to D-36, D-45, D-65 to D-67).
-- New tables, one INSERT ... SELECT over the existing companies (a short scan of a small table) and no change to an
-- existing table. A company that exists now starts NO_PLAN (D-199).

-- ---------------------------------------------------------------------------------------------------------------------
-- The plan catalog: reference data written only by migrations. A plan version is immutable: a change is a new version
-- (AC-PLAN-16), and existing subscriptions keep the version they were given.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE plan (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    code       text        NOT NULL,
    version    integer     NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_plan PRIMARY KEY (id),
    CONSTRAINT uq_plan_code_version UNIQUE (code, version),
    CONSTRAINT ck_plan_code CHECK (code IN ('TRIAL', 'STARTER', 'TEAM')),
    CONSTRAINT ck_plan_version CHECK (version >= 1),
    CONSTRAINT ck_plan_name CHECK (char_length(name) BETWEEN 1 AND 50)
);

CREATE TABLE plan_limit (
    plan_id   uuid    NOT NULL,
    limit_key text    NOT NULL,
    value     integer NOT NULL,
    CONSTRAINT pk_plan_limit PRIMARY KEY (plan_id, limit_key),
    CONSTRAINT fk_plan_limit_plan FOREIGN KEY (plan_id) REFERENCES plan (id),
    CONSTRAINT ck_plan_limit_key CHECK (limit_key IN ('TEAMS', 'ACTIVE_MEMBERS', 'ACTIVE_ASSESSMENTS')),
    CONSTRAINT ck_plan_limit_value CHECK (value >= 0)
);

CREATE TABLE plan_feature (
    plan_id     uuid NOT NULL,
    feature_key text NOT NULL,
    CONSTRAINT pk_plan_feature PRIMARY KEY (plan_id, feature_key),
    CONSTRAINT fk_plan_feature_plan FOREIGN KEY (plan_id) REFERENCES plan (id),
    CONSTRAINT ck_plan_feature_key CHECK (feature_key IN ('CORE_SCORING', 'REPORTS', 'HISTORICAL_COMPARISONS', 'CSV_EXPORT',
        'ADVANCED_ANALYTICS', 'BENCHMARKING', 'ANOMALY_DETECTION', 'EXTENDED_HISTORICAL_REPORTING'))
);

-- Seed: version 1 of the three plans (SPEC-PLAN, "Plans and limits"). The Trial has the full Team feature set.
INSERT INTO plan (code, version, name, created_at) VALUES
    ('TRIAL', 1, 'Trial', now()),
    ('STARTER', 1, 'Starter', now()),
    ('TEAM', 1, 'Team', now());

INSERT INTO plan_limit (plan_id, limit_key, value)
SELECT p.id, l.limit_key, l.value
  FROM plan p
  JOIN (VALUES ('TRIAL', 'TEAMS', 3), ('TRIAL', 'ACTIVE_MEMBERS', 15), ('TRIAL', 'ACTIVE_ASSESSMENTS', 3),
               ('STARTER', 'TEAMS', 3), ('STARTER', 'ACTIVE_MEMBERS', 15), ('STARTER', 'ACTIVE_ASSESSMENTS', 2),
               ('TEAM', 'TEAMS', 15), ('TEAM', 'ACTIVE_MEMBERS', 100), ('TEAM', 'ACTIVE_ASSESSMENTS', 10))
       AS l (code, limit_key, value) ON l.code = p.code AND p.version = 1;

INSERT INTO plan_feature (plan_id, feature_key)
SELECT p.id, f.feature_key
  FROM plan p
  JOIN (VALUES ('STARTER', 'CORE_SCORING'), ('STARTER', 'REPORTS'), ('STARTER', 'HISTORICAL_COMPARISONS'),
               ('STARTER', 'CSV_EXPORT'),
               ('TEAM', 'CORE_SCORING'), ('TEAM', 'REPORTS'), ('TEAM', 'HISTORICAL_COMPARISONS'), ('TEAM', 'CSV_EXPORT'),
               ('TEAM', 'ADVANCED_ANALYTICS'), ('TEAM', 'BENCHMARKING'), ('TEAM', 'ANOMALY_DETECTION'),
               ('TEAM', 'EXTENDED_HISTORICAL_REPORTING'),
               ('TRIAL', 'CORE_SCORING'), ('TRIAL', 'REPORTS'), ('TRIAL', 'HISTORICAL_COMPARISONS'),
               ('TRIAL', 'CSV_EXPORT'), ('TRIAL', 'ADVANCED_ANALYTICS'), ('TRIAL', 'BENCHMARKING'),
               ('TRIAL', 'ANOMALY_DETECTION'), ('TRIAL', 'EXTENDED_HISTORICAL_REPORTING'))
       AS f (code, feature_key) ON f.code = p.code AND p.version = 1;

-- ---------------------------------------------------------------------------------------------------------------------
-- A company's subscription: one row per company (the primary key). The state is what was last recorded; every decision
-- is made from the timestamps and the clock (AC-PLAN-10), so a late sweep never changes an answer.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE company_subscription (
    company_id        uuid        NOT NULL,
    plan_id           uuid,
    state             text        NOT NULL,
    trial_started_at  timestamptz,
    trial_ends_at     timestamptz,
    read_only_ends_at timestamptz,
    state_changed_at  timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_company_subscription PRIMARY KEY (company_id),
    CONSTRAINT fk_company_subscription_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT fk_company_subscription_plan FOREIGN KEY (plan_id) REFERENCES plan (id),
    -- PAST_DUE and CANCELED are reserved for a billing slice and are not allowed until it adds them (D-66).
    CONSTRAINT ck_company_subscription_state CHECK (state IN ('TRIALING', 'EXPIRED_READ_ONLY', 'EXPIRED_LOCKED', 'ACTIVE', 'NO_PLAN')),
    -- The trial timestamps come together (a NULL would pass a CHECK, so each is required to be present): exactly 14 days (336 hours, never calendar days, so no time zone can move them)
    -- of trial, then 14 days of read-only (D-34).
    CONSTRAINT ck_company_subscription_trial_times CHECK (
           (trial_started_at IS NULL AND trial_ends_at IS NULL AND read_only_ends_at IS NULL)
        OR (trial_started_at IS NOT NULL AND trial_ends_at IS NOT NULL AND read_only_ends_at IS NOT NULL
            AND trial_ends_at = trial_started_at + interval '336 hours'
            AND read_only_ends_at = trial_ends_at + interval '336 hours')),
    -- What each state needs: no plan and no trial for NO_PLAN, the Trial plan and its timestamps for a trial and its two
    -- expired states, a plan for ACTIVE. Which plan code fits which state is checked by a trigger below.
    CONSTRAINT ck_company_subscription_state_fields CHECK (
           (state = 'NO_PLAN' AND plan_id IS NULL AND trial_started_at IS NULL)
        OR (state IN ('TRIALING', 'EXPIRED_READ_ONLY', 'EXPIRED_LOCKED') AND plan_id IS NOT NULL AND trial_started_at IS NOT NULL)
        OR (state = 'ACTIVE' AND plan_id IS NOT NULL))
);

-- The sweep that records the two time transitions (Q-06).
CREATE INDEX ix_company_subscription_trial_end ON company_subscription (trial_ends_at) WHERE state = 'TRIALING';
CREATE INDEX ix_company_subscription_read_only_end ON company_subscription (read_only_ends_at) WHERE state = 'EXPIRED_READ_ONLY';

-- The plan versions a subscription uses.
CREATE INDEX ix_company_subscription_plan ON company_subscription (plan_id) WHERE plan_id IS NOT NULL;

-- A Trial is granted only for the first company a user creates (D-45). No foreign key to the company on purpose: the
-- purge of slice 007 deletes the company and its subscription but must not give the user a second Trial. The primary key
-- makes two simultaneous first creations by one user admit exactly one (AC-PLAN-25).
CREATE TABLE user_trial_grant (
    user_id    uuid        NOT NULL,
    company_id uuid        NOT NULL,
    granted_at timestamptz NOT NULL,
    CONSTRAINT pk_user_trial_grant PRIMARY KEY (user_id),
    CONSTRAINT fk_user_trial_grant_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Backfill (D-199, AC-PLAN-23): every company that exists now is NO_PLAN, and every user who already created a company
-- has used the first-company slot of D-45, so a company they create from now on is not a first one. The creator is the
-- actor of the audit event written when the company was created; a company with no such event grants nothing.
-- ---------------------------------------------------------------------------------------------------------------------
INSERT INTO company_subscription (company_id, state, state_changed_at, created_at, updated_at)
SELECT c.id, 'NO_PLAN', now(), now(), now() FROM company c;

INSERT INTO user_trial_grant (user_id, company_id, granted_at)
SELECT DISTINCT ON (a.actor_id) a.actor_id, a.target_id, a.occurred_at
  FROM audit_event a
 WHERE a.event_type = 'tenant.company.created' AND a.actor_type = 'user' AND a.actor_id IS NOT NULL
   AND a.target_id IS NOT NULL AND EXISTS (SELECT 1 FROM app_user u WHERE u.id = a.actor_id)
 ORDER BY a.actor_id, a.occurred_at, a.id;

-- ---------------------------------------------------------------------------------------------------------------------
-- Guards the application must not be able to get around.
-- ---------------------------------------------------------------------------------------------------------------------

-- A plan version never changes: no UPDATE and no DELETE on the catalog, and no row added to a version that a
-- subscription already uses (AC-PLAN-16).
CREATE FUNCTION refuse_plan_catalog_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'plan versions are immutable: add a new version instead (% on %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_plan_immutable BEFORE UPDATE OR DELETE ON plan
    FOR EACH ROW EXECUTE FUNCTION refuse_plan_catalog_change();
CREATE TRIGGER trg_plan_limit_immutable BEFORE UPDATE OR DELETE ON plan_limit
    FOR EACH ROW EXECUTE FUNCTION refuse_plan_catalog_change();
CREATE TRIGGER trg_plan_feature_immutable BEFORE UPDATE OR DELETE ON plan_feature
    FOR EACH ROW EXECUTE FUNCTION refuse_plan_catalog_change();

CREATE FUNCTION refuse_row_for_plan_in_use() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM company_subscription s WHERE s.plan_id = NEW.plan_id) THEN
        RAISE EXCEPTION 'plan version % is in use: add a new version instead', NEW.plan_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_plan_limit_not_in_use BEFORE INSERT ON plan_limit
    FOR EACH ROW EXECUTE FUNCTION refuse_row_for_plan_in_use();
CREATE TRIGGER trg_plan_feature_not_in_use BEFORE INSERT ON plan_feature
    FOR EACH ROW EXECUTE FUNCTION refuse_row_for_plan_in_use();

-- Which plan fits which state: the Trial plan for a trial and its two expired states, a paid plan for ACTIVE.
CREATE FUNCTION assert_subscription_plan_fits_state() RETURNS trigger AS $$
DECLARE
    plan_code text;
BEGIN
    IF NEW.plan_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT p.code INTO plan_code FROM plan p WHERE p.id = NEW.plan_id;
    IF NEW.state IN ('TRIALING', 'EXPIRED_READ_ONLY', 'EXPIRED_LOCKED') AND plan_code <> 'TRIAL' THEN
        RAISE EXCEPTION 'a trial subscription must use the Trial plan, not %', plan_code;
    END IF;
    IF NEW.state = 'ACTIVE' AND plan_code NOT IN ('STARTER', 'TEAM') THEN
        RAISE EXCEPTION 'an active subscription must use Starter or Team, not %', plan_code;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_subscription_plan_fits_state BEFORE INSERT OR UPDATE ON company_subscription
    FOR EACH ROW EXECUTE FUNCTION assert_subscription_plan_fits_state();

-- The state machine of SPEC-PLAN (AC-PLAN-17): TRIALING to EXPIRED_READ_ONLY to EXPIRED_LOCKED by time; anything but
-- ACTIVE (and ACTIVE itself, for a plan change) to ACTIVE by an operator. Nothing else, and the company never changes.
CREATE FUNCTION assert_subscription_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.company_id <> OLD.company_id THEN
        RAISE EXCEPTION 'a subscription cannot move to another company';
    END IF;
    IF NEW.state = OLD.state THEN
        RETURN NEW;
    END IF;
    IF (OLD.state = 'TRIALING' AND NEW.state = 'EXPIRED_READ_ONLY')
        OR (OLD.state = 'EXPIRED_READ_ONLY' AND NEW.state = 'EXPIRED_LOCKED')
        OR (NEW.state = 'ACTIVE' AND OLD.state IN ('TRIALING', 'EXPIRED_READ_ONLY', 'EXPIRED_LOCKED', 'NO_PLAN')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'subscription transition % to % is not allowed', OLD.state, NEW.state;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_subscription_transition BEFORE UPDATE ON company_subscription
    FOR EACH ROW EXECUTE FUNCTION assert_subscription_transition();
