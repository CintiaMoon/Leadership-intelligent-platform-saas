-- Assign a plan to a company, or change its plan (SPEC-PLAN, AC-PLAN-21, D-35, plan Q-15).
--
-- Run with psql in ONE transaction. All three variables are required:
--   psql --single-transaction --set ON_ERROR_STOP=1 -v company=<company uuid> -v plan=TEAM -v ticket=BILL-42 \
--        -f operator-assign-plan.sql
-- company: the id of ONE company (run the script once per company). plan: STARTER or TEAM, nothing else (the Trial is
-- granted by the application, never by an operator). ticket: the approval ticket, letters, digits, - and _.
-- If a variable is missing psql sends the text unchanged, the statement fails, and nothing is committed.
-- The integration test OperatorAssignPlanTests runs these statements against PostgreSQL. Keep them in step.
-- Read docs/runbooks/operations/plan-change.md first.
-- Run WITHOUT --single-transaction the script refuses to go on (see the marker statements below).
-- Idempotent: when the company is already ACTIVE on the latest version of that plan, nothing is changed and no audit
-- event is written (AC-PLAN-11).
-- Statements end at a semicolon at the end of a line, and comments contain none, so the test can split them.
--
-- The guards divide by the number of rows that must exist, so that psql stops with an error when a condition is not met.
-- (Do not write them as CASE ... ELSE 1 / 0: the planner folds that constant and fails always.)

-- 0. Stop here, with an error and nothing changed, if a variable is malformed.
SELECT 1 / (CASE WHEN :'ticket' ~ '^[A-Za-z0-9_-]{1,40}$' THEN 1 ELSE 0 END) AS ticket_ok;

SELECT 1 / (CASE WHEN :'plan' IN ('STARTER', 'TEAM') THEN 1 ELSE 0 END) AS plan_is_starter_or_team;

SELECT 1 / (CASE WHEN :'company' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN 1 ELSE 0 END)
       AS company_is_a_lower_case_uuid;

-- The script must run inside ONE transaction (psql --single-transaction): the locks, the guards and the writes depend on
-- it. A local setting lasts only to the end of the transaction, so in autocommit mode it is gone by the next statement
-- and the guard below stops the script before anything is written.
SELECT set_config('app.operator_plan_tx', 'yes', true) AS marker;

SELECT 1 / (CASE WHEN current_setting('app.operator_plan_tx', true) = 'yes' THEN 1 ELSE 0 END) AS inside_one_transaction;

-- Do not queue behind a long application transaction: give up after 5 seconds, as the application does (D-125).
SET LOCAL lock_timeout = '5s';

-- 1. Lock the company row first (D-32, D-125). The application takes this lock before it decides anything that depends
-- on the subscription, so while it is held no member change, invitation or transfer can be in flight in this company.
SELECT c.id AS company_id, c.name
  FROM company c
 WHERE c.id = :'company'::uuid
   FOR UPDATE;

SELECT 1 / count(*) AS company_exists
  FROM company c
 WHERE c.id = :'company'::uuid;

-- 2. Lock the subscription row. A company with no subscription row is a data fault, not something to repair here.
SELECT s.state, s.plan_id
  FROM company_subscription s
 WHERE s.company_id = :'company'::uuid
   FOR UPDATE;

SELECT 1 / count(*) AS subscription_exists
  FROM company_subscription s
 WHERE s.company_id = :'company'::uuid;

-- 3. The plan exists in the catalog. The newest version of the code is used (AC-PLAN-16).
SELECT 1 / count(*) AS plan_exists_in_the_catalog
  FROM plan p
 WHERE p.code = :'plan';

-- 4. The transition is one the state machine allows (AC-PLAN-17): an operator may move NO_PLAN, TRIALING,
-- EXPIRED_READ_ONLY and EXPIRED_LOCKED to ACTIVE, and ACTIVE to ACTIVE with another plan. The recorded state is used,
-- not the effective one: the answer is the same for both. A second line behind the transition trigger.
SELECT 1 / count(*) AS transition_is_allowed
  FROM company_subscription s
 WHERE s.company_id = :'company'::uuid
   AND s.state IN ('NO_PLAN', 'TRIALING', 'EXPIRED_READ_ONLY', 'EXPIRED_LOCKED', 'ACTIVE');

-- 5. Assign the plan. The row changes only when the company is not already ACTIVE on the newest version of this plan
-- (idempotence), and exactly then the audit events are written. The transition event names the states, the entitlement
-- event names the plan codes and the reason the versions. The reasons hold the ticket and the database login that ran
-- this (and the role it switched to, if different), since operators have no user ID yet. No address, no name.
WITH target AS (
    SELECT p.id, p.code, p.version
      FROM plan p
     WHERE p.code = :'plan'
     ORDER BY p.version DESC
     LIMIT 1),
prior AS (
    SELECT s.state, s.plan_id, op.code AS plan_code, op.version AS plan_version
      FROM company_subscription s LEFT JOIN plan op ON op.id = s.plan_id
     WHERE s.company_id = :'company'::uuid),
tail AS (
    SELECT ' ticket=' || :'ticket' || ' db_user=' || left(regexp_replace(session_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40)
           || CASE WHEN current_user <> session_user
                   THEN ' role=' || left(regexp_replace(current_user, '[^A-Za-z0-9_.-]', '_', 'g'), 40) ELSE '' END AS text),
changed AS (
    UPDATE company_subscription s
       SET plan_id = target.id, state = 'ACTIVE', state_changed_at = now(), updated_at = now(), version = s.version + 1
      FROM target
     WHERE s.company_id = :'company'::uuid AND (s.state <> 'ACTIVE' OR s.plan_id IS DISTINCT FROM target.id)
 RETURNING s.company_id),
transitioned AS (
    INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, before_value,
                             after_value, reason_code)
    SELECT now(), 'plan.subscription.transitioned', changed.company_id, 'operator', 'company_subscription',
           changed.company_id, 'success', prior.state, 'ACTIVE',
           CASE WHEN prior.state = 'ACTIVE' THEN 'operator_changed' ELSE 'operator_assigned' END || tail.text
      FROM changed CROSS JOIN prior CROSS JOIN tail
 RETURNING id)
INSERT INTO audit_event (occurred_at, event_type, company_id, actor_type, target_type, target_id, outcome, before_value,
                         after_value, reason_code)
SELECT now(), 'plan.entitlement.changed', changed.company_id, 'operator', 'company_subscription', changed.company_id,
       'success', prior.plan_code, target.code,
       'from_version=' || coalesce(prior.plan_version::text, 'none') || ' to_version=' || target.version || tail.text
  FROM changed CROSS JOIN prior CROSS JOIN target CROSS JOIN tail
 WHERE prior.plan_id IS DISTINCT FROM target.id;

-- 6. Check: the company is ACTIVE on the newest version of the plan. The first query shows it with the limit of the new
-- plan next to the members now ACTIVE. A downgrade below that number removes and suspends nobody (Q-18): the members stay,
-- and until usage is below the limit a reactivation or an invitation acceptance is refused with MEMBER_LIMIT_REACHED.
SELECT s.state, p.code AS plan, p.version AS plan_version,
       (SELECT l.value FROM plan_limit l WHERE l.plan_id = p.id AND l.limit_key = 'ACTIVE_MEMBERS') AS active_members_limit,
       (SELECT count(*) FROM membership m WHERE m.company_id = s.company_id AND m.status = 'ACTIVE') AS active_members
  FROM company_subscription s JOIN plan p ON p.id = s.plan_id
 WHERE s.company_id = :'company'::uuid;

SELECT 1 / count(*) AS assignment_is_consistent
  FROM company_subscription s JOIN plan p ON p.id = s.plan_id
 WHERE s.company_id = :'company'::uuid AND s.state = 'ACTIVE' AND p.code = :'plan'
   AND p.version = (SELECT max(n.version) FROM plan n WHERE n.code = :'plan');
