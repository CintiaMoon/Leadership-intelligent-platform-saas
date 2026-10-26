-- Transfer the ownership of a flagged company to an active member (SPEC-OWN, AC-OWN-18, D-54, D-142).
--
-- Run with psql in ONE transaction. All five variables are required:
--   psql --single-transaction --set ON_ERROR_STOP=1 -v email=Old.Owner@Example.com -v recipient=new.owner@example.com \
--        -v company=<company uuid> -v ticket=SEC-125 -v non_admin_ok=no -f operator-ownership-transfer.sql
-- email: the address of the disabled Owner (trimmed and lower-cased here). recipient: the address of the member who
-- becomes the Owner. company: the id of ONE flagged company (run the script once per company). ticket: the approval
-- ticket, letters, digits, - and _. non_admin_ok: yes or no. Only yes lets the recipient be an Agent or a Viewer, for the
-- case the security owner named in the ticket. The recipient is otherwise an Admin.
-- If a variable is missing psql sends the text unchanged, the statement fails, and nothing is committed.
-- The integration test OperatorOwnershipTransferTests runs these statements against PostgreSQL. Keep them in step.
-- Read docs/runbooks/operations/operator-ownership-transfer.md first.
-- Not idempotent on purpose: a second run finds the flag cleared and stops with an error, changing nothing.
-- Statements end at a semicolon at the end of a line, and comments contain none, so the test can split them.
--
-- The guards divide by the number of rows that must exist, so that psql stops with an error when a condition is not met.
-- (Do not write them as CASE ... ELSE 1 / 0: the planner folds that constant and fails always.)

-- 0. Stop here, with an error and nothing changed, if a variable is malformed.
SELECT 1 / (CASE WHEN :'ticket' ~ '^[A-Za-z0-9_-]{1,40}$' THEN 1 ELSE 0 END) AS ticket_ok;

SELECT 1 / (CASE WHEN :'non_admin_ok' IN ('yes', 'no') THEN 1 ELSE 0 END) AS non_admin_ok_is_yes_or_no;

SELECT 1 / (CASE WHEN :'company' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN 1 ELSE 0 END)
       AS company_is_a_lower_case_uuid;

-- Do not queue behind a long application transaction: give up after 5 seconds, as the application does (D-125).
SET LOCAL lock_timeout = '5s';

-- 1. Lock the company row first (D-32, D-125). Every ownership and membership change in the application takes this lock
-- first, so while it is held nobody can accept, cancel, remove or demote anyone in this company.
SELECT c.id AS company_id, c.name
  FROM company c
 WHERE c.id = :'company'::uuid
   FOR UPDATE;

-- 2. Lock the recipient's account, so that it cannot be disabled between the checks below and the commit. The disable
-- procedure also takes the company lock before it touches an account.
SELECT u.id AS recipient_user_id
  FROM app_user u
 WHERE u.email_normalized = lower(btrim(:'recipient'))
   FOR UPDATE;

-- 3. The company exists and is flagged for ownership resolution.
SELECT 1 / count(*) AS company_is_flagged
  FROM company c
 WHERE c.id = :'company'::uuid AND c.ownership_resolution_at IS NOT NULL;

-- 4. The address names the company's Owner, whose membership is suspended and whose account is disabled.
SELECT 1 / count(*) AS owner_is_suspended_and_disabled
  FROM membership m JOIN app_user u ON u.id = m.user_id
 WHERE m.company_id = :'company'::uuid AND u.email_normalized = lower(btrim(:'email'))
   AND m.role = 'OWNER' AND m.status = 'SUSPENDED' AND u.status = 'DISABLED';

-- 5. The recipient is an ACTIVE member of this company with an ACTIVE account and a verified email, and is an Admin, or,
-- only when non_admin_ok is yes, an Agent or a Viewer. Never the Owner, a suspended or removed member.
SELECT 1 / count(*) AS recipient_is_eligible
  FROM membership m JOIN app_user u ON u.id = m.user_id
 WHERE m.company_id = :'company'::uuid AND u.email_normalized = lower(btrim(:'recipient'))
   AND m.status = 'ACTIVE' AND m.role <> 'OWNER'
   AND (m.role = 'ADMIN' OR (:'non_admin_ok' = 'yes' AND m.role IN ('AGENT', 'VIEWER')))
   AND u.status = 'ACTIVE' AND u.email_verified_at IS NOT NULL;

-- 6. Lock the two memberships, in id order (D-182).
SELECT m.id AS membership_id, m.role, m.status
  FROM membership m JOIN app_user u ON u.id = m.user_id
 WHERE m.company_id = :'company'::uuid AND m.status IN ('ACTIVE', 'SUSPENDED')
   AND u.email_normalized IN (lower(btrim(:'email')), lower(btrim(:'recipient')))
 ORDER BY m.id
   FOR UPDATE OF m;

-- 7. Cancel a pending ownership transfer of this company: its Owner can no longer act and the Owner is changing.
-- The unique index allows one pending transfer per company. Nobody is emailed about the cancellation.
WITH cancelled AS (
    UPDATE ownership_transfer t
       SET status = 'CANCELLED', resolution = 'cancelled_operator_transfer', resolved_at = now(), resolved_by = NULL,
           updated_at = now(), version = t.version + 1
     WHERE t.company_id = :'company'::uuid AND t.status = 'PENDING'
 RETURNING t.id)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'ownership.transfer.cancelled', :'company'::uuid, 'operator', 'ownership_transfer', id, 'success',
       'PENDING', 'CANCELLED', 'operator_ownership_transfer ticket=' || :'ticket'
  FROM cancelled;

-- 8. Revoke the invitations the old Owner left pending: a suspended member cannot invite (AC-OWN-21).
WITH revoked AS (
    UPDATE invitation i
       SET status = 'REVOKED', token_hash = NULL, revoked_at = now(), revoked_by = NULL, updated_at = now(),
           version = i.version + 1
      FROM app_user u
     WHERE i.company_id = :'company'::uuid AND i.status = 'PENDING' AND u.id = i.invited_by
       AND u.email_normalized = lower(btrim(:'email'))
 RETURNING i.id)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'invitation.revoked', :'company'::uuid, 'operator', 'invitation', id, 'success', 'PENDING', 'REVOKED',
       'operator_ownership_transfer ticket=' || :'ticket'
  FROM revoked;

-- 9. Demote the old Owner to Admin. The membership stays SUSPENDED until the security owner decides otherwise, because
-- the usual reason for a disable is a suspected compromise. Demote first: at most one Owner membership may exist at any
-- moment, and the check that one always exists runs at commit.
WITH demoted AS (
    UPDATE membership m
       SET role = 'ADMIN', updated_at = now(), version = m.version + 1
      FROM app_user u
     WHERE u.id = m.user_id AND m.company_id = :'company'::uuid AND u.email_normalized = lower(btrim(:'email'))
       AND m.role = 'OWNER' AND m.status = 'SUSPENDED'
 RETURNING m.id)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'membership.role.changed', :'company'::uuid, 'operator', 'membership', id, 'success', 'OWNER', 'ADMIN',
       'operator_ownership_transfer ticket=' || :'ticket'
  FROM demoted;

-- 10. Promote the recipient.
WITH seat AS (
    SELECT m.id, m.role AS old_role
      FROM membership m JOIN app_user u ON u.id = m.user_id
     WHERE m.company_id = :'company'::uuid AND u.email_normalized = lower(btrim(:'recipient'))
       AND m.status = 'ACTIVE' AND m.role <> 'OWNER'),
promoted AS (
    UPDATE membership m
       SET role = 'OWNER', updated_at = now(), version = m.version + 1
      FROM seat
     WHERE m.id = seat.id
 RETURNING m.id)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'membership.role.changed', :'company'::uuid, 'operator', 'membership', seat.id, 'success', seat.old_role,
       'OWNER', 'operator_ownership_transfer ticket=' || :'ticket'
  FROM seat JOIN promoted ON promoted.id = seat.id;

-- 11. Clear the flag.
WITH cleared AS (
    UPDATE company c
       SET ownership_resolution_at = NULL, updated_at = now(), version = c.version + 1
     WHERE c.id = :'company'::uuid AND c.ownership_resolution_at IS NOT NULL
 RETURNING c.id)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, reason_code)
SELECT now(), 'company.ownership_resolution.cleared', id, 'operator', 'company', id, 'success',
       'operator_ownership_transfer ticket=' || :'ticket'
  FROM cleared;

-- 12. The audit event of the whole transfer. The reason names the ticket and the database role that ran this, since
-- operators have no user ID yet. Only identifiers and the ticket: no address, no name.
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, reason_code)
VALUES (now(), 'ownership.transfer.operator_completed', :'company'::uuid, 'operator', 'company', :'company'::uuid,
        'success', 'operator_ownership_transfer ticket=' || :'ticket' || ' db_user='
        || left(regexp_replace(current_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40));

-- 13. Queue the notices through the outbox (ADR-003), to be sent after commit: one to the new Owner and one to each
-- ACTIVE Admin of the company, each with an ACTIVE account and a verified email. Identifiers only. The old Owner is a
-- SUSPENDED member now, so is not among them, and the sender checks again when it sends. A repeated key is ignored.
INSERT INTO outbox_event (event_type, payload, idempotency_key, next_attempt_at, created_at)
SELECT 'OWNERSHIP_TRANSFER_OPERATOR_COMPLETED',
       jsonb_build_object('companyId', m.company_id::text, 'userId', m.user_id::text,
                          'addressee', CASE WHEN m.role = 'OWNER' THEN 'NEW_OWNER' ELSE 'ADMIN' END),
       'ownership-operator-' || m.company_id || '-' || :'ticket' || '-' || m.user_id, now(), now()
  FROM membership m JOIN app_user u ON u.id = m.user_id
 WHERE m.company_id = :'company'::uuid AND m.status = 'ACTIVE' AND m.role IN ('OWNER', 'ADMIN')
   AND u.status = 'ACTIVE' AND u.email_verified_at IS NOT NULL
ON CONFLICT (idempotency_key) DO NOTHING;

-- 14. Check: exactly one Owner membership, the flag is cleared, and nothing is pending.
SELECT c.id AS company_id, c.ownership_resolution_at,
       (SELECT count(*) FROM membership o WHERE o.company_id = c.id AND o.role = 'OWNER'
           AND o.status IN ('ACTIVE', 'SUSPENDED')) AS owner_memberships,
       (SELECT count(*) FROM ownership_transfer t WHERE t.company_id = c.id AND t.status = 'PENDING') AS pending_transfers
  FROM company c
 WHERE c.id = :'company'::uuid;
