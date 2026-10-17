# REST API Contracts and OpenAPI

## When to use
Use when adding/changing a backend endpoint, request/response DTO, error format, or frontend/backend interaction.

## Procedure
1. Read the feature spec and API contract guidelines.
2. Define method, path, authentication, CSRF, authorization, request schema, response schema, status codes, and error cases in OpenAPI.
3. Confirm tenant context is not treated as authorization by itself.
4. Keep error responses stable and avoid leaking stack traces or sensitive internals.
5. Update backend DTOs/controller behavior and frontend API types/client in the same feature slice.
6. Add contract tests for success, validation, unauthenticated, forbidden, conflict, expired/consumed token, and rate-limit behavior as applicable.
7. Validate the OpenAPI document in CI and document breaking changes.

## Rules
- Do not expose JPA entities.
- Do not put credentials or session secrets in URLs.
- Do not silently change response semantics.
- Treat example endpoints in docs as illustrative until approved.
