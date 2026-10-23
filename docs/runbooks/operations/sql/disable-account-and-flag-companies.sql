-- Disable an account and apply the D-54 effects on every company it owns (SPEC-MEM, AC-MEM-14, AC-MEM-18).
--
-- Run with psql in ONE transaction, with the account's normalized address as the variable:
--   psql --single-transaction --set ON_ERROR_STOP=1 -v email=person@example.com -f disable-account-and-flag-companies.sql
-- The integration test OwnerAccountDisableProcedureTests runs these statements against PostgreSQL. Keep them in step.
-- Read docs/runbooks/operations/operator-account-disable.md first. Idempotent: a second run changes nothing.
-- Statements end at a semicolon at the end of a line, and comments contain none, so the test can split them.

-- 1. The account. The session filter rejects a DISABLED account on its next request, even before step 2.
WITH disabled AS (
    UPDATE app_user
       SET status = 'DISABLED', updated_at = now(), version = version + 1
     WHERE email_normalized = :'email' AND status <> 'DISABLED'
 RETURNING id)
INSERT INTO audit_event (occurred_at, event_type, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'account.status.changed', 'operator', 'user', id, 'success', 'ACTIVE', 'DISABLED', 'operator_disable'
  FROM disabled;

-- 2. Its sessions (spring_session_attributes rows go with them).
DELETE FROM spring_session
 WHERE principal_name IN (SELECT id::text FROM app_user WHERE email_normalized = :'email');

-- 3. Lock the company rows first, in a fixed order (D-125). The result lists the companies this account owns.
SELECT c.id AS company_id, c.name
  FROM company c
 WHERE c.id IN (SELECT m.company_id
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE u.email_normalized = :'email' AND m.role = 'OWNER' AND m.status = 'ACTIVE')
 ORDER BY c.id
   FOR UPDATE;

-- 4. D-54: the Owner membership stays the single Owner membership but is suspended, and the company is flagged for
-- ownership resolution. Owner-only operations and company deletion become unavailable until an audited,
-- operator-assisted transfer to an active member (slice 005). The Owner trigger counts a suspended Owner.
WITH suspended AS (
    UPDATE membership m
       SET status = 'SUSPENDED', status_changed_at = now(), updated_at = now(), version = m.version + 1
      FROM app_user u
     WHERE u.id = m.user_id AND u.email_normalized = :'email' AND m.role = 'OWNER' AND m.status = 'ACTIVE'
 RETURNING m.id, m.company_id),
flagged AS (
    UPDATE company c
       SET ownership_resolution_at = COALESCE(c.ownership_resolution_at, now()), updated_at = now(),
           version = c.version + 1
      FROM suspended s
     WHERE c.id = s.company_id
 RETURNING c.id),
membership_audit AS (
    INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome,
                             before_value, after_value, reason_code)
    SELECT now(), 'membership.suspended', company_id, 'operator', 'membership', id, 'success', 'ACTIVE',
           'SUSPENDED', 'owner_account_disabled'
      FROM suspended)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome,
                         reason_code)
SELECT now(), 'company.ownership_resolution.flagged', id, 'operator', 'company', id, 'success',
       'owner_account_disabled'
  FROM flagged;

-- 5. Check: every company this account owned is now flagged, and each still has exactly one Owner membership.
SELECT c.id AS company_id, c.ownership_resolution_at,
       (SELECT count(*) FROM membership o WHERE o.company_id = c.id AND o.role = 'OWNER'
           AND o.status IN ('ACTIVE', 'SUSPENDED')) AS owner_memberships
  FROM company c
 WHERE c.id IN (SELECT m.company_id
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE u.email_normalized = :'email' AND m.role = 'OWNER');
