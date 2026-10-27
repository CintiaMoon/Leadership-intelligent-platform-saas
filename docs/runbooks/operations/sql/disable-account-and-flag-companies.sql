-- Disable an account and apply the D-54 effects on every company it owns (SPEC-MEM, AC-MEM-14, AC-MEM-18).
--
-- Run with psql in ONE transaction. Both variables are required:
--   psql --single-transaction --set ON_ERROR_STOP=1 -v email=Person@Example.com -v ticket=SEC-123 \
--        -f disable-account-and-flag-companies.sql
-- email: the account's address (trimmed and lower-cased here). ticket: the approval ticket, letters, digits, - and _.
-- If a variable is missing psql sends the text unchanged, the statement fails, and nothing is committed.
-- The integration test OwnerAccountDisableProcedureTests runs these statements against PostgreSQL. Keep them in step.
-- Read docs/runbooks/operations/operator-account-disable.md first. Idempotent: a second run changes nothing.
-- Statements end at a semicolon at the end of a line, and comments contain none, so the test can split them.

-- 0. Stop here, with an error and nothing changed, if the ticket is malformed or the account does not exist. The
-- division by zero is the error. (Do not write it as CASE ... ELSE 1 / 0: the planner folds that constant and fails always.)
SELECT 1 / (CASE WHEN :'ticket' ~ '^[A-Za-z0-9_-]{1,40}$' THEN 1 ELSE 0 END) AS ticket_ok;

SELECT 1 / count(*) AS account_exists FROM app_user WHERE email_normalized = lower(btrim(:'email'));

-- The script must run inside ONE transaction (psql --single-transaction): the locks, the checks and the writes depend on
-- it. A local setting lasts only to the end of the transaction, so in autocommit mode it is gone by the next statement
-- and the guard below stops the script before anything is written.
SELECT set_config('app.operator_script_tx', 'yes', true) AS marker;

SELECT 1 / (CASE WHEN current_setting('app.operator_script_tx', true) = 'yes' THEN 1 ELSE 0 END) AS inside_one_transaction;

-- Do not queue behind a long application transaction: give up after 5 seconds, as the application does (D-125).
SET LOCAL lock_timeout = '5s';

-- 1. Lock the company rows first, in a fixed order (D-125), before touching the account. The result lists the
-- companies this account owns.
SELECT c.id AS company_id, c.name
  FROM company c
 WHERE c.id IN (SELECT m.company_id
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE u.email_normalized = lower(btrim(:'email')) AND m.role = 'OWNER' AND m.status = 'ACTIVE')
 ORDER BY c.id
   FOR UPDATE;

-- 2. The account. The session filter rejects a DISABLED account on its next request, even before step 3.
-- The audit reason names the ticket and the database role that ran this, since operators have no user ID yet.
WITH disabled AS (
    UPDATE app_user
       SET status = 'DISABLED', updated_at = now(), version = version + 1
     WHERE email_normalized = lower(btrim(:'email')) AND status <> 'DISABLED'
 RETURNING id)
INSERT INTO audit_event (occurred_at, event_type, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'account.status.changed', 'operator', 'user', id, 'success', 'ACTIVE', 'DISABLED',
       'operator_disable ticket=' || :'ticket' || ' db_user=' || left(regexp_replace(session_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40) || CASE WHEN current_user <> session_user THEN ' role=' || left(regexp_replace(current_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40) ELSE '' END
  FROM disabled;

-- 3. Its sessions (spring_session_attributes rows go with them).
DELETE FROM spring_session
 WHERE principal_name IN (SELECT id::text FROM app_user WHERE email_normalized = lower(btrim(:'email')));

-- 4. D-54: the Owner membership stays the single Owner membership but is suspended, and the company is flagged for
-- ownership resolution. Owner-only operations and company deletion become unavailable until an audited,
-- operator-assisted transfer to an active member (slice 005). The Owner trigger counts a suspended Owner.
WITH suspended AS (
    UPDATE membership m
       SET status = 'SUSPENDED', status_changed_at = now(), updated_at = now(), version = m.version + 1
      FROM app_user u
     WHERE u.id = m.user_id AND u.email_normalized = lower(btrim(:'email')) AND m.role = 'OWNER' AND m.status = 'ACTIVE'
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
           'SUSPENDED', 'owner_account_disabled ticket=' || :'ticket'
      FROM suspended)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome,
                         reason_code)
SELECT now(), 'company.ownership_resolution.flagged', id, 'operator', 'company', id, 'success',
       'owner_account_disabled ticket=' || :'ticket'
  FROM flagged;

-- 5. Check: every company this account owned is now flagged, and each still has exactly one Owner membership.
SELECT c.id AS company_id, c.ownership_resolution_at,
       (SELECT count(*) FROM membership o WHERE o.company_id = c.id AND o.role = 'OWNER'
           AND o.status IN ('ACTIVE', 'SUSPENDED')) AS owner_memberships
  FROM company c
 WHERE c.id IN (SELECT m.company_id
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE u.email_normalized = lower(btrim(:'email')) AND m.role = 'OWNER');

-- 6. Stop, rolling everything back, unless every company this account owns is now flagged and still has exactly one
-- Owner membership.
SELECT 1 / (CASE WHEN NOT EXISTS (
    SELECT 1 FROM company c
     WHERE c.id IN (SELECT m.company_id
                      FROM membership m JOIN app_user u ON u.id = m.user_id
                     WHERE u.email_normalized = lower(btrim(:'email')) AND m.role = 'OWNER')
       AND (c.ownership_resolution_at IS NULL
            OR (SELECT count(*) FROM membership o WHERE o.company_id = c.id AND o.role = 'OWNER'
                   AND o.status IN ('ACTIVE', 'SUSPENDED')) <> 1)) THEN 1 ELSE 0 END) AS disable_is_consistent;
