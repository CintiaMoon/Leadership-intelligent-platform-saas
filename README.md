# Spec-Driven Multi-Tenant SaaS — Spring Boot Edition

This repository is a starter documentation and Claude Code workflow bundle for building a secure multi-tenant SaaS application with a Next.js frontend and a Spring Boot REST API.

## Technology baseline
- Frontend: Next.js App Router, TypeScript, Tailwind CSS
- Backend: Java 21, Spring Boot 4.x (select and pin a supported patch release before implementation)
- Security: Spring Security; server-side sessions using Spring Session JDBC as the initial proposal
- Persistence: PostgreSQL, Spring Data JPA, Hibernate
- Schema migrations: Flyway SQL migrations; Flyway is the production schema source of truth
- API: REST/JSON with an OpenAPI contract
- Testing: JUnit 5, Spring Boot Test, Spring Security Test, Testcontainers for PostgreSQL; frontend tests and E2E tests as selected by the team
- Build: Maven (see CLAUDE.md)

## Repository layout
- `CLAUDE.md`: global implementation rules for Claude Code
- `docs/product/`: product vision and glossary
- `docs/requirements/`: functional and non-functional requirements
- `docs/architecture/`: architecture, invariants, threat model, data model, and ADRs
- `docs/specs/`: feature-level specifications and acceptance criteria
- `docs/api/`: API contract guidance
- `docs/runbooks/`: repeatable feature workflow
- `.claude/agents/`: focused agent definitions
- `.claude/skills/`: repeatable engineering procedures

## Start here
1. Read `CLAUDE.md`, the product vision, requirements, architecture, invariants, and threat model.
2. Resolve the open decisions listed in the architecture and authentication spec. In particular, choose the browser-to-API session topology and build tool.
3. Update and approve ADRs before implementing authentication or persistence.
4. Approve the feature spec and API contract before implementation.
5. Implement one vertical slice, test it against real PostgreSQL, and obtain an independent review.

## Important status note
Feature specifications are drafts until the product owner approves them: SPEC-AUTH, SPEC-TEN, and SPEC-MEM are marked Approved, and the other four remain Draft with open decisions (see `docs/architecture/decision-register.md`). Architecture choices described as proposed are not automatically approved. Do not treat this bundle as production-ready code or as evidence that tests have passed.

## Product scope
- Sign-up, email verification, login/logout, server-side sessions, CSRF, rate limiting, and account lockout
- Company/tenant creation and Owner / Admin / Agent / Viewer roles
- Single-use, time-limited invitations matched to a verified email
- Ownership transfer with explicit acceptance and atomic state change; protect the last Owner
- Trial (14 days), Starter, and Team plans with server-enforced entitlements
- Company deletion grace period, permanent purge, and retained tombstone
