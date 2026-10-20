-- Bumped whenever the password is replaced by the user's reset (and by later credential changes). A session records the
-- value it was opened with and is rejected once the account has moved on, so a login that was racing a reset cannot
-- leave a surviving session behind (security review, Phase 6).
ALTER TABLE app_user ADD COLUMN credentials_version bigint NOT NULL DEFAULT 0;
