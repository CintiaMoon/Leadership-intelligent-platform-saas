# Functional Requirements

## Status
Draft — assign owners and approve before implementation.

## Authentication (AUTH)
- AUTH-001: A user can sign up with an email address and password if password-based signup is approved.
- AUTH-002: Email addresses are normalized consistently before uniqueness checks.
- AUTH-003: A user can verify email ownership using a time-limited, single-use token.
- AUTH-004: An unverified account cannot perform actions requiring verified identity.
- AUTH-005: An eligible user can log in and log out.
- AUTH-006: Protected requests validate an authoritative server-side session.
- AUTH-007: Sessions can be revoked and expire according to policy.
- AUTH-008: Authentication endpoints apply rate limits and account-abuse controls.
- AUTH-009: Account lockout and recovery follow an approved policy.
- AUTH-010: Authentication failures do not unnecessarily disclose whether an account exists.

## Company and membership (TEN)
- TEN-001: An eligible authenticated user can create a company.
- TEN-002: Company creation and initial Owner membership are atomic.
- TEN-003: A company can have memberships for users with roles Owner, Admin, Agent, or Viewer.
- TEN-004: All company-owned data access is scoped to a verified tenant context.
- TEN-005: Membership status and role are evaluated server-side for protected operations.
- TEN-006: The final active Owner cannot be removed or demoted.

## Invitations (INV)
- INV-001: An authorized user can invite an email address to a company.
- INV-002: Invitations are single-use and time-limited.
- INV-003: Invitation acceptance requires a verified email matching the invited email.
- INV-004: Expired, revoked, or consumed invitations cannot be accepted.
- INV-005: Concurrent acceptance attempts create at most one membership.

## Ownership transfer (OWN)
- OWN-001: An eligible Owner can initiate a transfer to an eligible recipient.
- OWN-002: The recipient must explicitly accept the transfer.
- OWN-003: Transfer completion is atomic and concurrency-safe.
- OWN-004: Expired, cancelled, or completed transfers cannot be reused.
- OWN-005: Transfer completion preserves company ownership invariants and is audited.

## Plans and entitlements (PLAN)
- PLAN-001: A company can start a 14-day Trial under the approved start-time policy.
- PLAN-002: Starter and Team plans have explicit, approved entitlements.
- PLAN-003: Entitlements and usage limits are enforced server-side.
- PLAN-004: Trial expiry behavior is defined and enforced.
- PLAN-005: Concurrent operations cannot exceed enforced quotas where strict quotas are required.

## Company lifecycle (DEL)
- DEL-001: An authorized Owner can request company deletion.
- DEL-002: Deletion enters a pending state and follows an approved grace period.
- DEL-003: Cancellation/restoration is allowed only under the approved policy.
- DEL-004: Permanent purge is safe to retry and handles partial failure.
- DEL-005: A tombstone is retained with only approved metadata.
- DEL-006: Purge completion is verifiable and audited without retaining purged content.
