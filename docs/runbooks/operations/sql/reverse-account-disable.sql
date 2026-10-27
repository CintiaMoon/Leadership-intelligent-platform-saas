-- Undo a mistaken disable made with disable-account-and-flag-companies.sql (SPEC-MEM, D-54, D-142).
--
-- Run with psql in ONE transaction. Both variables are required:
--   psql --single-transaction --set ON_ERROR_STOP=1 -v email=Person@Example.com -v ticket=SEC-124 \
--        -f reverse-account-disable.sql
-- email: the account's address (trimmed and lower-cased here). ticket: the approval ticket of THIS reversal.
-- If a variable is missing psql sends the text unchanged, the statement fails, and nothing is committed.
-- The integration test OwnerAccountReversalProcedureTests runs these statements against PostgreSQL. Keep them in step.
-- Read docs/runbooks/operations/operator-account-disable.md first. Idempotent: a second run changes nothing.
-- Statements end at a semicolon at the end of a line, and comments contain none, so the test can split them.
--
-- An Owner membership is restored only if ALL of these hold, so the script cannot undo anything else:
--   it is still the Owner role (so no ownership transfer has happened since),
--   it is SUSPENDED and its company is still flagged,
--   and the audit trail holds the operator suspension written by the disable script for exactly this membership.

-- 0. Stop here, with an error and nothing changed, if the ticket is malformed or the account does not exist.
SELECT 1 / (CASE WHEN :'ticket' ~ '^[A-Za-z0-9_-]{1,40}$' THEN 1 ELSE 0 END) AS ticket_ok;

SELECT 1 / count(*) AS account_exists FROM app_user WHERE email_normalized = lower(btrim(:'email'));

-- The script must run inside ONE transaction (psql --single-transaction): the locks, the checks and the writes depend on
-- it. A local setting lasts only to the end of the transaction, so in autocommit mode it is gone by the next statement
-- and the guard below stops the script before anything is written.
SELECT set_config('app.operator_script_tx', 'yes', true) AS marker;

SELECT 1 / (CASE WHEN current_setting('app.operator_script_tx', true) = 'yes' THEN 1 ELSE 0 END) AS inside_one_transaction;

-- Do not queue behind a long application transaction: give up after 5 seconds, as the application does (D-125).
SET LOCAL lock_timeout = '5s';

-- 1. Lock the company rows first, in a fixed order (D-125). The result lists the companies this script may restore.
SELECT c.id AS company_id, c.name
  FROM company c
 WHERE c.id IN (SELECT m.company_id
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE u.email_normalized = lower(btrim(:'email')) AND m.role = 'OWNER' AND m.status = 'SUSPENDED')
   AND c.ownership_resolution_at IS NOT NULL
 ORDER BY c.id
   FOR UPDATE;

-- 2. Restore the Owner memberships and clear the flags, with audit rows. The Owner trigger counts the suspended
-- Owner today and the active Owner afterwards, so each company has exactly one Owner membership throughout.
WITH restored AS (
    UPDATE membership m
       SET status = 'ACTIVE', status_changed_at = now(), updated_at = now(), version = m.version + 1
      FROM app_user u, company c
     WHERE u.id = m.user_id AND u.email_normalized = lower(btrim(:'email'))
       AND m.role = 'OWNER' AND m.status = 'SUSPENDED'
       AND c.id = m.company_id AND c.ownership_resolution_at IS NOT NULL
       AND EXISTS (SELECT 1 FROM audit_event a
                    WHERE a.event_type = 'membership.suspended' AND a.target_id = m.id AND a.actor_type = 'operator'
                      AND a.reason_code LIKE 'owner_account_disabled%')
 RETURNING m.id, m.company_id),
cleared AS (
    UPDATE company c
       SET ownership_resolution_at = NULL, updated_at = now(), version = c.version + 1
      FROM restored r
     WHERE c.id = r.company_id
 RETURNING c.id),
membership_audit AS (
    INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome,
                             before_value, after_value, reason_code)
    SELECT now(), 'membership.reactivated', company_id, 'operator', 'membership', id, 'success', 'SUSPENDED',
           'ACTIVE', 'owner_account_restored ticket=' || :'ticket' || ' db_user=' || left(regexp_replace(session_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40) || CASE WHEN current_user <> session_user THEN ' role=' || left(regexp_replace(current_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40) ELSE '' END
      FROM restored)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome,
                         reason_code)
SELECT now(), 'company.ownership_resolution.cleared', id, 'operator', 'company', id, 'success',
       'owner_account_restored ticket=' || :'ticket'
  FROM cleared;

-- 3. The account itself. Sessions are not restored: the user logs in again.
WITH enabled AS (
    UPDATE app_user
       SET status = 'ACTIVE', updated_at = now(), version = version + 1
     WHERE email_normalized = lower(btrim(:'email')) AND status = 'DISABLED'
 RETURNING id)
INSERT INTO audit_event (occurred_at, event_type, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'account.status.changed', 'operator', 'user', id, 'success', 'DISABLED', 'ACTIVE',
       'operator_enable ticket=' || :'ticket' || ' db_user=' || left(regexp_replace(session_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40) || CASE WHEN current_user <> session_user THEN ' role=' || left(regexp_replace(current_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40) ELSE '' END
  FROM enabled;

-- 4. Check: the companies this account owns, whether each is still flagged, and which membership holds the Owner role.
-- A company that is still flagged was NOT restored (for example because ownership was transferred): do not force it.
SELECT c.id AS company_id, c.ownership_resolution_at,
       (SELECT o.status FROM membership o WHERE o.company_id = c.id AND o.role = 'OWNER'
           AND o.status IN ('ACTIVE', 'SUSPENDED')) AS owner_membership_status,
       (SELECT count(*) FROM membership o WHERE o.company_id = c.id AND o.role = 'OWNER'
           AND o.status IN ('ACTIVE', 'SUSPENDED')) AS owner_memberships
  FROM company c
 WHERE c.id IN (SELECT m.company_id
                  FROM membership m JOIN audit_event a ON a.target_id = m.id AND a.event_type = 'membership.suspended'
                                                     AND a.actor_type = 'operator'
                  JOIN app_user u ON u.id = m.user_id
                 WHERE u.email_normalized = lower(btrim(:'email')));

-- 5. Stop, rolling everything back, unless the account is ACTIVE and every company where it still holds the Owner role
-- has exactly one Owner membership.
SELECT 1 / (CASE WHEN NOT EXISTS (SELECT 1 FROM app_user u WHERE u.email_normalized = lower(btrim(:'email'))
                                     AND u.status <> 'ACTIVE')
                  AND NOT EXISTS (
    SELECT 1 FROM company c
     WHERE c.id IN (SELECT m.company_id
                      FROM membership m JOIN app_user u ON u.id = m.user_id
                     WHERE u.email_normalized = lower(btrim(:'email')) AND m.role = 'OWNER')
       AND (SELECT count(*) FROM membership o WHERE o.company_id = c.id AND o.role = 'OWNER'
               AND o.status IN ('ACTIVE', 'SUSPENDED')) <> 1) THEN 1 ELSE 0 END) AS reversal_is_consistent;
