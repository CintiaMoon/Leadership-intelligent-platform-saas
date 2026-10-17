# Product Vision

## Status
Draft — requires product-owner approval.

## Product summary
Build a secure multi-tenant SaaS application where users can create and manage companies, collaborate under role-based access controls, use plan-based entitlements, and securely manage company lifecycle and deletion.

## Primary capabilities
- Account signup, email verification, login/logout, and secure session management.
- Company creation and membership with Owner, Admin, Agent, and Viewer roles.
- Time-limited, single-use invitations bound to a verified email address.
- Explicitly accepted ownership transfer with concurrency-safe invariants.
- Trial, Starter, and Team plans with server-enforced entitlements.
- Company deletion with a grace period, permanent purge, and a minimal tombstone.

## Product principles
- Security and tenant isolation are foundational, not optional features.
- Business rules must be explicit and testable.
- Destructive operations must be auditable, idempotent where appropriate, and recoverable during any approved grace period.
- User-facing errors should be understandable without leaking sensitive account or tenant information.
- Data retention must be intentional and documented.

## Open decisions
- Target customer profile and primary workflows.
- Starter and Team entitlements and limits.
- Billing/payment scope.
- Company deletion grace-period duration.
- Restoration policy during the grace period.
- Tombstone and audit-log retention requirements.
- Availability, performance, and recovery objectives.
