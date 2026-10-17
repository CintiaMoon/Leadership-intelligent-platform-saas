# Multi-Tenant Design

## When to use
Use whenever code reads, creates, updates, exports, or deletes company-owned data or evaluates company-specific entitlements.

## Procedure
1. Identify the authenticated principal.
2. Resolve requested company context from trusted server-side data.
3. Verify active membership.
4. Evaluate the permission required for the operation.
5. Apply tenant scoping in the data-access query itself.
6. Validate that related records belong to the same tenant.
7. Review indirect access paths, including IDs in URLs, forms, and nested resources.
8. Test cross-tenant access using separate users and companies.

## Mandatory invariants
- Never trust a client-supplied role.
- Possession of a company ID is not authorization.
- Never return tenant-owned records without an access check.
- Hidden UI controls are not a security boundary.
- Revoked or inactive membership cannot authorize requests.

## Completion checklist
- Positive authorization tests pass.
- Cross-tenant negative tests pass.
- Related-record ownership is verified.
- No unscoped query remains in the reviewed path.
