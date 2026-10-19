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
- `apps/web`: Next.js (App Router, TypeScript, Tailwind CSS) web client
- `services/api`: Java 21 / Spring Boot REST API (Maven wrapper)
- `contracts/openapi.yaml`: OpenAPI contract (no business endpoints yet)
- `infra/local/compose.yaml`: local-only PostgreSQL via Docker Compose
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

## Local development
Prerequisites: JDK 21, Node.js 24 with npm, Docker. Set `JAVA_HOME` to a JDK 21 (the Maven wrapper uses it).

Pinned versions (the ADR-002 baseline is in `services/api/pom.xml` and `apps/web/package.json`):
Spring Boot 4.1.1, Java 21, Maven 3.10.0 (wrapper), PostgreSQL 17.6 (Compose and Testcontainers),
Next.js 16.4.2, React 19.3.0, TypeScript 5.9.3, Tailwind CSS 4.3.3, Vitest 5.0.5.

```
cp .env.example .env          # then edit the local-only password
docker compose --env-file .env -f infra/local/compose.yaml up -d --wait
```
If port 5432 is taken on your machine, set `POSTGRES_PORT` and `DB_URL` in `.env` accordingly.

Backend (`services/api`):
```
./mvnw verify                 # unit and integration tests; Testcontainers needs Docker
DB_PASSWORD=... ./mvnw spring-boot:run
```
The schema is owned by Flyway (`src/main/resources/db/migration`); Hibernate only validates it (`ddl-auto: validate`).
The API denies every request except `/actuator/health` until SPEC-AUTH is implemented. The JVM runs in UTC.

Frontend (`apps/web`):
```
npm ci
npm run lint && npm run typecheck && npm test && npm run build
npm run dev
```
Playwright (ADR-006) is not installed yet; it is added with the first slice that needs an E2E journey.
Auth.js is intentionally not used: sessions are server-side in Spring (ADR-001).

## Important status note
Feature specifications are drafts until the product owner approves them: SPEC-AUTH, SPEC-TEN, and SPEC-MEM are marked Approved, and the other four remain Draft with open decisions (see `docs/architecture/decision-register.md`). Architecture choices described as proposed are not automatically approved. Do not treat this bundle as production-ready code or as evidence that tests have passed.

## Product scope
- Sign-up, email verification, login/logout, server-side sessions, CSRF, rate limiting, and account lockout
- Company/tenant creation and Owner / Admin / Agent / Viewer roles
- Single-use, time-limited invitations matched to a verified email
- Ownership transfer with explicit acceptance and atomic state change; protect the last Owner
- Trial (14 days), Starter, and Team plans with server-enforced entitlements
- Company deletion grace period, permanent purge, and retained tombstone
