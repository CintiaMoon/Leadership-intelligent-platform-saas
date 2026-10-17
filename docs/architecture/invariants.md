# Security and Business Invariants

## Identity
- An unverified email cannot perform actions requiring verification.
- Passwords are never stored or logged in plaintext.
- Verification and invitation tokens are single-use and time-limited.
- Session revocation takes effect on subsequent authorized requests within the documented revocation model.

## Tenant isolation
- Every tenant-owned resource is accessed within a verified tenant context.
- Membership must be active.
- Client-supplied tenant IDs never confer authorization.
- Related records must belong to the same tenant.
- Data access must not depend on hidden UI controls.

## Roles and ownership
- Roles are evaluated server-side.
- The Owner/Admin/Agent/Viewer permission matrix is explicit and approved.
- The Owner cannot be removed or demoted except through ownership transfer.
- Ownership transfer requires explicit recipient acceptance.
- Ownership changes are concurrency-safe and audited.

## Entitlements
- Trial duration is 14 days according to a defined server-side time policy.
- All plan limits are checked on the server.
- The UI never decides whether an entitlement is granted.
- Strict quotas use atomic accounting or another concurrency-safe strategy.

## Deletion
- Scheduled deletion blocks operations according to the approved policy.
- Cancellation is allowed only under the approved policy.
- Purge is idempotent and safe to retry.
- A tombstone contains only approved retained metadata.
- Purged data cannot be recovered through ordinary application APIs.
- Backup and external-storage limitations are accurately documented.

# Membership and Ownership Invariants

1. Every active company has exactly one Owner (decision D-43), and that Owner is active except while the company is flagged for ownership resolution after an operator disables the Owner's account (decision D-54).
2. Only an Owner may grant or revoke the Admin role.
3. Admins may manage Agent and Viewer memberships but may not manage Owners or other Admins.
4. Agents and Viewers may not assign roles or remove other members.
5. Ownership changes must use a dedicated ownership-transfer workflow with explicit acceptance; ordinary role updates cannot assign the Owner role.
6. The Owner cannot leave the company, be removed, or permanently delete their account until ownership is resolved or the company is deleted through its approved lifecycle.
7. Members may leave a company themselves, except when doing so would violate the active-Owner invariant.
8. Membership removal and role changes must take effect for all subsequent authorization checks after the transaction commits.
9. Tenant authorization must be evaluated server-side on every protected request; UI restrictions are not an authorization boundary.
10. Disabling a user account blocks authentication globally and invalidates or rejects existing authenticated sessions.
11. Account deletion, membership removal, ownership transfer, and company deletion are separate operations with distinct authorization, audit, and retention rules.
12. All membership and ownership changes must be audited with the tenant, actor, target, operation, relevant before/after state, and timestamp.
