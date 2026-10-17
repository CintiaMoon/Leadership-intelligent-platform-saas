# REST API Contract Guidelines

## Contract-first rule
Before implementing a feature that crosses frontend/backend boundaries, update and approve its OpenAPI contract. The contract describes externally observable behavior; Java DTOs and TypeScript client types must conform to it.

## Conventions
- Use `/api/v1/...` for public application endpoints unless an ADR selects a different versioning policy.
- Use resource-oriented nouns and HTTP methods consistently.
- Use JSON request/response DTOs, never JPA entities.
- Define required fields, formats, bounds, and validation errors.
- Use RFC 9457 `application/problem+json` as the error schema (`type`, `title`, `status`, `detail`, `instance`, plus a stable application `code` and optional field errors); see ADR-005. Do not leak stack traces or internal implementation details.
- Document authentication, CSRF requirements, authorization requirements, idempotency, rate limits, and expected status codes per operation.
- Treat tenant identifiers as selectors only; the backend must verify the caller's membership and permission.
- Do not expose raw invitation/verification tokens in logs. Tokens may appear in user-targeted links only as required and must be single-use, expiring, and stored as hashes.
- Define pagination and sorting conventions before list endpoints are implemented.
- Make breaking changes explicit; do not silently alter existing response semantics.

## Suggested endpoint families (illustrative; approve exact paths and semantics)
- Authentication: `/api/v1/auth/signup`, `/api/v1/auth/verify-email`, `/api/v1/auth/login`, `/api/v1/auth/logout`, `/api/v1/auth/session`
- Companies: `/api/v1/companies`, `/api/v1/companies/{companyId}`
- Memberships: `/api/v1/companies/{companyId}/memberships`
- Invitations: `/api/v1/companies/{companyId}/invitations`, `/api/v1/invitations/accept` (the raw token is sent in the request body, never in the path or query string; decision D-44)
- Ownership: `/api/v1/companies/{companyId}/ownership-transfers`
- Plans/entitlements: `/api/v1/companies/{companyId}/entitlements`, plus internal/admin plan catalog endpoints as approved
- Lifecycle: deletion request/cancel endpoints under `/api/v1/companies/{companyId}`; purge is an internal job, not a public endpoint

Do not assume these example endpoints are approved requirements. Finalize endpoint names, methods, DTOs, and status codes in OpenAPI with each feature spec.

## Authentication notes
If sessions use cookies, document CSRF requirements for state-changing requests. Do not put bearer credentials or session secrets in URLs. Avoid returning sensitive session identifiers in response bodies.

## Contract verification
- Validate OpenAPI syntax in CI.
- Test controller behavior against contract expectations.
- Generate or type-check the frontend API client from the contract where practical.
- Include unauthorized, forbidden, validation, conflict, expired-token, and rate-limited responses.
