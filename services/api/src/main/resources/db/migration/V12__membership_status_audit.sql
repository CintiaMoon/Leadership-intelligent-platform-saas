-- Status records, audit before/after values and the ownership-resolution flag (SPEC-MEM, D-129, D-130, D-135).
-- Additive only: every new column is nullable with no default, so ADD COLUMN is a metadata change and no row is
-- rewritten. No backfill: rows created before this migration have no status change to record.

-- Who last changed a membership's status, and when (AC-MEM-13). The audit trail keeps the history.
ALTER TABLE membership
    ADD COLUMN status_changed_at timestamptz,
    ADD COLUMN status_changed_by uuid,
    ADD CONSTRAINT fk_membership_status_changed_by FOREIGN KEY (status_changed_by) REFERENCES app_user (id);

-- Before and after values, only for role and status (docs/architecture/audit-events.md). The pattern admits the
-- enum names (OWNER, PENDING_DELETION, ...) and nothing that could hold a name, an address or free text.
-- NOT VALID then VALIDATE keeps the lock short on a table that grows without bound: ADD CONSTRAINT would scan it
-- under an exclusive lock, VALIDATE CONSTRAINT scans it under a lock that allows reads and writes.
ALTER TABLE audit_event
    ADD COLUMN before_value text,
    ADD COLUMN after_value text,
    ADD CONSTRAINT ck_audit_event_before_value CHECK (before_value IS NULL OR before_value ~ '^[A-Z_]{1,32}$') NOT VALID,
    ADD CONSTRAINT ck_audit_event_after_value CHECK (after_value IS NULL OR after_value ~ '^[A-Z_]{1,32}$') NOT VALID;
ALTER TABLE audit_event VALIDATE CONSTRAINT ck_audit_event_before_value;
ALTER TABLE audit_event VALIDATE CONSTRAINT ck_audit_event_after_value;

-- Set by the operator procedure when a company's Owner account is disabled (D-54). Null means not flagged.
ALTER TABLE company
    ADD COLUMN ownership_resolution_at timestamptz;
