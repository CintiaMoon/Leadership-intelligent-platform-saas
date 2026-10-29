-- SPEC-PLAN, Phase 6 question 4: close two ways an UPDATE could rewrite a subscription without a state change.
--
-- V15's transition trigger returned early when the state did not change, so a same-state UPDATE could move the trial
-- dates or swap the plan. Now:
--   1. The three trial timestamps never change once they are set (the 14 + 14 day windows are fixed when the trial
--      starts, D-34). Going from NULL to a value is still possible.
--   2. The plan of a subscription changes only (a) together with the move to ACTIVE, or (b) from one paid plan to another
--      while ACTIVE inside the operator script, which sets the transaction-local marker app.operator_plan_tx first.
--      A time transition keeps the Trial plan it has. The marker is a guard against mistakes, not a security boundary:
--      one database login does everything today (D-88).
-- No data is read or rewritten, so there is nothing to backfill and the lock is held only for the function replacement.

CREATE OR REPLACE FUNCTION assert_subscription_transition() RETURNS trigger AS $$
BEGIN
    IF NEW.company_id <> OLD.company_id THEN
        RAISE EXCEPTION 'a subscription cannot move to another company';
    END IF;
    IF (OLD.trial_started_at IS NOT NULL AND NEW.trial_started_at IS DISTINCT FROM OLD.trial_started_at)
        OR (OLD.trial_ends_at IS NOT NULL AND NEW.trial_ends_at IS DISTINCT FROM OLD.trial_ends_at)
        OR (OLD.read_only_ends_at IS NOT NULL AND NEW.read_only_ends_at IS DISTINCT FROM OLD.read_only_ends_at) THEN
        RAISE EXCEPTION 'the trial dates of a subscription never change once they are set';
    END IF;
    IF NEW.state = OLD.state THEN
        IF NEW.plan_id IS DISTINCT FROM OLD.plan_id
            AND NOT (NEW.state = 'ACTIVE' AND coalesce(current_setting('app.operator_plan_tx', true), '') = 'yes') THEN
            RAISE EXCEPTION 'the plan of a subscription changes only through the operator plan script';
        END IF;
        RETURN NEW;
    END IF;
    IF (OLD.state = 'TRIALING' AND NEW.state = 'EXPIRED_READ_ONLY')
        OR (OLD.state = 'EXPIRED_READ_ONLY' AND NEW.state = 'EXPIRED_LOCKED') THEN
        IF NEW.plan_id IS DISTINCT FROM OLD.plan_id THEN
            RAISE EXCEPTION 'a time transition keeps the plan of the subscription';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.state = 'ACTIVE' AND OLD.state IN ('TRIALING', 'EXPIRED_READ_ONLY', 'EXPIRED_LOCKED', 'NO_PLAN') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'subscription transition % to % is not allowed', OLD.state, NEW.state;
END;
$$ LANGUAGE plpgsql;
