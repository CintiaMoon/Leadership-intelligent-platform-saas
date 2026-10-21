-- A membership never moves to another company (review of SPEC-TEN, security finding 5).
-- The Owner trigger from V10 checks only NEW.company_id on UPDATE, so moving an Owner membership would leave the old
-- company without an Owner. The JPA mapping already marks company_id as not updatable; this makes it a database rule.
CREATE FUNCTION forbid_membership_company_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'membership % cannot move to another company', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_membership_company_immutable
    BEFORE UPDATE OF company_id ON membership
    FOR EACH ROW
    WHEN (OLD.company_id IS DISTINCT FROM NEW.company_id)
    EXECUTE FUNCTION forbid_membership_company_change();
