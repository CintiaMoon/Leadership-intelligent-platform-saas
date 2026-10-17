# Audit Event Catalogue

- Status: Proposed. The convention was approved on 2026-10-17 (D-74); the event list below is derived from the specs and must be checked against each audit acceptance criterion before it is Approved.
- Retention: 12 months is proposed (D-40, still open).

## Convention
- Name format: `<domain>.<entity>.<action>`, lowercase, for example `membership.role.changed`.
- Required fields: event ID, timestamp (UTC), company ID (null for global events), actor type (`user`, `operator`, `system`) and ID, target type and ID, outcome (`success` or `denied`), request correlation ID, and a reason code where relevant.
- Values may be recorded before and after only for role, status, and plan fields.
- Never recorded: passwords, tokens, cookies, email bodies, free-text content, or purged company content (CLAUDE.md principle 14; AC-DEL-11).
- Storage: an append-only table. The application database role has insert and select only; no update or delete. Purge keeps audit events for the retention period (SPEC-DEL).
- Denied authorization attempts on the operations below are recorded with outcome `denied`.

## Events by domain (from the specs)
| Domain | Events | Source |
|---|---|---|
| auth | `auth.user.registered`, `auth.email.verified`, `auth.login.succeeded`, `auth.login.failed`, `auth.session.revoked`, `auth.password.reset_requested`, `auth.password.reset_completed`, `auth.step_up.succeeded` | SPEC-AUTH |
| account | `account.status.changed` (operator disable or enable) | SPEC-AUTH, SPEC-MEM |
| company | `company.created`, `company.settings.changed`, `company.ownership_resolution.flagged` | SPEC-TEN, SPEC-MEM |
| membership | `membership.created`, `membership.role.changed`, `membership.suspended`, `membership.reactivated`, `membership.removed`, `membership.left` | SPEC-TEN, SPEC-MEM |
| invitation | `invitation.created`, `invitation.resent`, `invitation.revoked`, `invitation.accepted`, `invitation.expired` | SPEC-INV |
| ownership | `ownership.transfer.initiated`, `ownership.transfer.accepted`, `ownership.transfer.declined`, `ownership.transfer.cancelled`, `ownership.transfer.expired`, `ownership.transfer.operator_completed` | SPEC-OWN, SPEC-MEM |
| plan | `plan.subscription.transitioned`, `plan.entitlement.changed` | SPEC-PLAN |
| deletion | `company.deletion.requested`, `company.deletion.cancelled`, `company.purge.completed`, `company.purge.failed` | SPEC-DEL |
| export | `export.requested`, `export.downloaded`, `export.expired` | D-73 |

## Open items
- Check every audit acceptance criterion in SPEC-AUTH through SPEC-DEL against this list and add any missing event name.
- Confirm retention (D-40).
