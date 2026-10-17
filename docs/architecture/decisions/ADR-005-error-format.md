# ADR-005: Error Response Format

- Status: Accepted (suggestion approved by the user on 2026-10-16)
- Date: 2026-10-16
- Decision owners: TBD
- Related: decision register D-09; `docs/api/contract-guidelines.md`

## Context
`CLAUDE.md` requires consistent error responses and the contract guidelines need a single schema.

## Decision
All API errors use RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, and `instance`, plus a stable application `code` and optional field errors for validation failures. Responses never include stack traces or internal details.

## Alternatives considered
A custom error envelope.

## Consequences
- The OpenAPI contract defines the problem schema once and references it from every operation.
- The frontend maps `code` values to user-facing messages.
- Authentication failures must still avoid disclosing account existence (AC-AUTH-13).

## Verification
- Contract tests for unauthorized, forbidden, validation, conflict, expired-token, and rate-limited responses.
