# Start Here: Build the SaaS with Claude Code

This guide takes the documentation bundle from specifications to a working application. Follow the steps in order. Do not ask Claude Code to implement the entire SaaS in one prompt.

## How to use this guide

- **Existing documentation** means files already in this bundle.
- **Application files to create** means files Claude Code should create in the actual codebase.
- Use Claude Code from the repository root so it can read `CLAUDE.md`, `.claude/agents/`, `.claude/skills/`, and `docs/`.
- Treat all feature specs as drafts until you have resolved open questions and approved them.
- Never claim a test passed unless you or Claude Code actually ran it and saw the result.

## Target architecture

- `apps/web`: Next.js App Router, TypeScript, Tailwind CSS.
- `services/api`: Java 21, Spring Boot, Spring Security, Spring Data JPA/Hibernate, PostgreSQL, Flyway.
- `contracts/openapi.yaml`: agreed REST API contract used by backend and frontend.
- PostgreSQL is the source of truth for application data; Flyway versioned SQL migrations are the production schema source of truth.
- Start with a modular monolith, not microservices.

---

## Step 0 — Prepare your machine and workspace

### Actions

1. Install and verify Git, Node.js LTS, a package manager, Java 21 JDK, Docker Desktop (or compatible Docker Engine), and your chosen IDE.
2. Install Claude Code and verify that it starts in a terminal.
3. Extract this bundle into a new project directory. The extracted `claude_spec_driven_saas/` directory should become the repository root, so `CLAUDE.md` and `.claude/` are at the root.
4. Initialize Git and make an initial commit of the documentation before editing it.
5. Create a private remote repository if you want backup and collaboration. Never commit secrets.

### Commands (run in a terminal)

```bash
git init
git add .
git commit -m "Add SaaS specifications and Claude Code workflow"
java -version
node --version
npm --version
git --version
docker --version
```

Run `claude` from the project root to start Claude Code. If your operating system or shell differs, use the equivalent commands.

### Existing files to inspect

- `README.md`
- `CLAUDE.md`
- `docs/product/vision.md`
- `docs/requirements/functional-requirements.md`
- `docs/requirements/non-functional-requirements.md`
- `docs/architecture/architect.md`
- `docs/architecture/invariants.md`
- `docs/architecture/threat-model.md`

### Prompt 0 — Repository inventory (read-only)

```text
Read CLAUDE.md and README.md first. Inspect the documentation bundle and report:
1. What is present and what is missing to start implementation.
2. Every unresolved product decision and architecture decision.
3. Any contradiction between specs, architecture, data model, and security requirements.
4. Which decisions must be made before code can safely be written.
5. A suggested implementation sequence.
Do not create or modify files. Do not invent decisions. Treat feature specs as drafts.
```

**Checkpoint:** you understand the current documents and have not generated application code yet.

---

## Step 1 — Complete project-wide decisions before coding

Open these existing files and edit them yourself, or ask Claude Code to propose changes for your approval:

1. `docs/architecture/decisions/ADR-001-session-and-browser-api-boundary.md`
2. `CLAUDE.md`
3. `docs/architecture/architect.md`
4. `docs/architecture/data-model.md`
5. `docs/architecture/invariants.md`
6. `docs/architecture/threat-model.md`
7. `docs/requirements/non-functional-requirements.md`

### Decisions you must make

**A. Browser/API topology.** Choose one:
- **BFF:** browser calls same-origin Next.js Route Handlers, which forward to Spring Boot through a carefully allowlisted proxy; or
- **Direct API:** browser calls Spring Boot, ideally behind same-origin reverse-proxy routing. If cross-origin, configure explicit CORS and credentials.

Spring Security must remain the authoritative backend security enforcement point. Do not have Auth.js and Spring Security maintain unrelated authoritative sessions. CORS does not replace CSRF protection.

**B. Build tool.** Choose Gradle or Maven. Pin versions and use the same choice in local development and CI.

**C. Authentication policy.** Decide whether passwords are in scope, password policy, password hashing, session idle and absolute expiry, email token expiry, lockout/rate limits, recovery, and duplicate-account responses.

**D. Product rules.** Decide who can create a company, role permissions, invitation expiry/resend/revoke behavior, transfer behavior, plan differences and quota policy, grace-period duration, purge policy, and tombstone retention.

**E. Operations.** Decide local email strategy, production email provider, deployment environment, secrets strategy, backup/restore requirements, logging/monitoring, and how scheduled purge work is operated.

### Prompt 1 — Decision review (do not implement)

```text
Read CLAUDE.md, docs/architecture/architect.md, docs/architecture/data-model.md,
docs/architecture/invariants.md, docs/architecture/threat-model.md,
docs/architecture/decisions/ADR-001-session-and-browser-api-boundary.md,
and all seven feature specs.

Create a decision register with columns: ID, question, why it matters, safe options,
recommended option, files affected, and whether it blocks implementation.
Do not silently select a business policy. Flag security decisions that need explicit approval.
Do not write application code. Propose edits as a reviewable patch, but wait for approval.
```

### Edits to make after reviewing the result

- Change ADR-001 status from `Proposed` to `Accepted` only after you choose the topology and document cookie, CSRF, session, and proxy details.
- Record the selected build tool and pinned framework versions in `CLAUDE.md` and an ADR (create `docs/architecture/decisions/ADR-002-build-and-version-baseline.md`).
- Update the affected feature specs to replace `TBD`/open questions with approved decisions.
- Keep a decision open if you genuinely do not know yet. Ask for options and trade-offs rather than allowing the coding agent to guess.

**Checkpoint:** no security-critical architectural decision is silently unresolved.

---

## Step 2 — Approve the specs and add traceability

### Existing files

- `docs/specs/001-authentication/spec.md`
- `docs/specs/002-tenants/spec.md`
- `docs/specs/003-memberships/spec.md`
- `docs/specs/004-invitations/spec.md`
- `docs/specs/005-ownership-transfer/spec.md`
- `docs/specs/006-plans-entitlements/spec.md`
- `docs/specs/007-company-deletion/spec.md`
- `docs/requirements/functional-requirements.md`
- `docs/requirements/non-functional-requirements.md`

### Prompt 2 — Spec audit

```text
Audit each feature specification against the approved architecture and requirements.
For every feature, list: actors, preconditions, main flow, alternate/error flows,
authorization rules, tenant scope, state transitions, acceptance criteria, required
API operations, persistence changes, events/jobs, and tests.
Identify ambiguous wording, missing negative cases, and contradictions. Do not invent
business decisions. Update files only after showing me the proposed changes and receiving
approval. Keep acceptance criteria uniquely identified and testable.
```

### Required edits

For each feature spec:
- Change `Status: Draft` to `Status: Approved` only after you review and accept it.
- Replace unresolved questions with decisions or explicitly record them as deferred and explain why they do not block this feature.
- Ensure acceptance criteria are measurable and have stable IDs.
- Add explicit forbidden actions and error behavior, not just the happy path.

Create `docs/implementation/feature-status.md` with columns: feature, spec status, plan status, implementation status, tests, security review, and outstanding issues. Keep it updated after each feature.

**Checkpoint:** the first feature you intend to implement has approved acceptance criteria and no blocking unknowns.

---

## Step 3 — Create the application skeleton

Do this only after the architecture choices are approved.

### Expected structure

```text
apps/web/                         # Next.js frontend
services/api/                     # Spring Boot API
contracts/openapi.yaml             # API contract
infra/local/                       # optional local infrastructure files
infra/docker-compose.yml           # local PostgreSQL and optional dependencies
.env.example                       # names of required variables, no secrets
.gitignore
```

Keep the documentation, `.claude/agents/`, and `.claude/skills/` at the repository root. Do not move `CLAUDE.md` into a subdirectory.

### Prompt 3 — Scaffold only

```text
Create the minimum runnable repository skeleton according to the approved ADRs.
Create apps/web as a Next.js App Router + TypeScript + Tailwind application.
Create services/api as a Java 21 Spring Boot application using the approved build tool,
Spring Web, Spring Security, Validation, Spring Data JPA, PostgreSQL, Flyway, Actuator,
and test dependencies including Testcontainers PostgreSQL.
Create contracts/openapi.yaml with API metadata and no unapproved business endpoints.
Add a local-only Docker Compose setup for PostgreSQL, .env.example, .gitignore, and
README instructions. Do not add production secrets or business features yet.
Use compatible stable versions and record exact versions. Do not use Hibernate
schema auto-update. Do not add Auth.js as a second authoritative session system.
Run the frontend and backend build/test commands and report the actual output.
```

### Files to create or edit

- `apps/web/package.json`, `apps/web/tsconfig.json`, `apps/web/next.config.*`, `apps/web/app/layout.tsx`, `apps/web/app/page.tsx`, Tailwind configuration, and frontend source files generated by the initializer.
- `services/api/build.gradle` or `services/api/pom.xml` (choose one), `services/api/src/main/java/.../SaasApiApplication.java`, `services/api/src/main/resources/application.yml`, and a health endpoint/configuration.
- `contracts/openapi.yaml`
- `infra/docker-compose.yml` (or the agreed local infrastructure path)
- `.env.example`, `.gitignore`, root `README.md`
- Update `CLAUDE.md` with actual commands and versions.

### Verify manually

1. Start PostgreSQL locally.
2. Start Spring Boot and verify its health endpoint.
3. Start Next.js and load the home page.
4. Run the backend build/tests and frontend lint/typecheck/build.
5. Commit the working skeleton.

Do not begin authentication until both applications start cleanly.

---

## Step 4 — Define API and database conventions

### Existing files to edit

- `docs/api/contract-guidelines.md`
- `docs/architecture/data-model.md`
- `docs/architecture/invariants.md`
- `docs/architecture/architect.md`
- `contracts/openapi.yaml`

### Prompt 4 — Contract and persistence rules

```text
Review the API guidelines and approved architecture. Propose conventions for API path
naming, DTOs, validation, pagination, error responses, request correlation, versioning,
OpenAPI validation, entity mapping, Flyway migration naming, indexes, constraints,
transaction boundaries, and integration testing against PostgreSQL.
Add these conventions to the appropriate docs and CLAUDE.md after showing the proposed
changes. Do not design unapproved feature endpoints. Ensure Flyway is the schema source
of truth and JPA entities are never serialized directly.
```

### Files to create

- `services/api/src/main/java/.../shared/api/ApiErrorResponse.java` (or the approved error DTO name)
- `services/api/src/main/java/.../shared/api/GlobalExceptionHandler.java`
- `services/api/src/test/...` support for integration tests
- Optional API validation command/configuration in the root scripts or CI

Keep the shared error format small and stable. Do not overbuild generic frameworks before a real feature needs them.

---

## Step 5 — Implement authentication as the first vertical slice

Use `docs/specs/001-authentication/spec.md` and the accepted ADR-001. This feature is a prerequisite for protected tenant operations.

### Planning prompt

```text
Read the approved authentication spec, ADR-001, threat model, invariants, API guidelines,
and data model. Do not code. Produce a file-by-file plan for the smallest end-to-end
slice: signup, verification, login, current session, and logout. Include the OpenAPI
operations, DTOs, Flyway SQL, Spring Security/session/CSRF configuration, email adapter,
frontend pages, tests, local email testing approach, and exact commands. Identify all
remaining blockers and wait for approval.
```

### Implement in this order

1. Approve endpoint paths, status codes, request/response schemas, and CSRF token flow in `contracts/openapi.yaml`.
2. Add the first Flyway migration under `services/api/src/main/resources/db/migration/`. Check actual migration history before assigning its version.
3. Implement user/account entities and repositories; persist verification token hashes, not raw tokens.
4. Implement the email delivery interface and a local development/testing adapter.
5. Implement Spring Security, Spring Session JDBC, CSRF, cookie/session settings, and rate-limiting integration according to approved decisions.
6. Implement registration, verification, login, session lookup, and logout services/controllers.
7. Implement Next.js signup, login, verification, and logged-in state using the approved BFF/direct API topology.
8. Add unit tests, PostgreSQL/Testcontainers tests, Spring Security tests, and browser E2E tests.

### Likely application files

Backend:
- `services/api/src/main/java/.../auth/api/AuthController.java`
- `.../auth/application/RegistrationService.java`
- `.../auth/application/EmailVerificationService.java`
- `.../auth/application/AuthenticationService.java`
- `.../auth/domain/User.java`
- `.../auth/domain/EmailVerificationToken.java`
- `.../auth/infrastructure/UserRepository.java`
- `.../config/SecurityConfig.java`
- `services/api/src/main/resources/db/migration/V001__create_authentication_tables.sql` (example only; choose the next unused version)

Frontend:
- `apps/web/app/(auth)/signup/page.tsx`
- `apps/web/app/(auth)/login/page.tsx`
- `apps/web/app/(auth)/verify-email/page.tsx`
- `apps/web/lib/api/client.ts`
- `apps/web/lib/api/auth.ts`

Tests should mirror package structure under `services/api/src/test/java/...`; add frontend component/E2E tests using the approved test tools.

### Implementation prompt

Use prompt 2 in `docs/runbooks/claude-code-prompts.md`, replacing `[SPEC-ID]` with `001-authentication`, and ask Claude Code to implement one approved sub-slice at a time if the change is too large.

### Review and close

Use prompts 4 and 5 from `docs/runbooks/claude-code-prompts.md`. Verify actual test results and mark the feature complete in `docs/implementation/feature-status.md` only when all critical criteria have evidence.

---

## Step 6 — Implement the remaining features in dependency order

For each feature, repeat: plan → approve → contract/schema → backend → frontend → tests → independent review → close. Do not ask one agent to edit a shared migration or OpenAPI file at the same time as another agent.

### Feature 002 — Company/tenant creation

**Spec:** `docs/specs/002-tenants/spec.md`

**Key files:**
- Backend: `tenant/api/TenantController.java`, `tenant/application/CreateTenantService.java`, `tenant/domain/Tenant.java`, `tenant/infrastructure/TenantRepository.java`
- Membership integration: approved membership service to create the initial Owner in the same transaction
- Migration: next unused Flyway version for tenant tables
- Frontend: `apps/web/app/companies/new/page.tsx`, `apps/web/app/companies/page.tsx`, `apps/web/lib/api/tenants.ts`
- Tests: service, controller, PostgreSQL integration, unauthorized/duplicate request tests

**Prompt:**
```text
Plan SPEC 002-tenants only. Read the spec, membership spec, data model, invariants,
OpenAPI guidelines, and feature workflow. Do not code. Define tenant creation, initial
Owner creation in the same transaction, DTOs, migration, authorization, tests, and exact
files. List open decisions and wait for approval.
```

### Feature 003 — Memberships and roles

**Spec:** `docs/specs/003-memberships/spec.md`

**Key files:**
- Backend: `membership/api/MembershipController.java`, `membership/application/MembershipService.java`, `membership/application/TenantAuthorizationService.java`, `membership/domain/Membership.java`, `membership/domain/CompanyRole.java`, `membership/infrastructure/MembershipRepository.java`
- Migration: next unused Flyway version for membership tables and constraints
- Frontend: `apps/web/app/companies/[companyId]/members/page.tsx`, member table/role components, `apps/web/lib/api/memberships.ts`
- Tests: role matrix, tenant isolation, forbidden operations, last Owner safety

**Prompt:**
```text
Plan SPEC 003-memberships only. Create a permission matrix for Owner, Admin, Agent,
and Viewer and map each permission to API operations and tests. Define tenant scoping,
unique constraints, role-change/removal rules, and last-Owner protection. Do not code or
invent missing policy. Wait for approval.
```

### Feature 004 — Invitations

**Spec:** `docs/specs/004-invitations/spec.md`

**Key files:**
- Backend: `invitation/api/InvitationController.java`, `invitation/application/CreateInvitationService.java`, `invitation/application/AcceptInvitationService.java`, `invitation/domain/Invitation.java`, `invitation/infrastructure/InvitationRepository.java`, email adapter
- Migration: invitation table, token hash, expiry, status, tenant and recipient fields
- Frontend: company invitations page, acceptance page, `apps/web/lib/api/invitations.ts`
- Tests: expiry, replay, revoked token, verified-email match, concurrent acceptance

**Prompt:**
```text
Plan SPEC 004-invitations. Specify token generation/storage, verified-email matching,
role assignment authorization, expiry/revocation, single-use semantics, transaction and
locking strategy, delivery retries, and concurrency tests. Store only token hashes. Do
not code until the plan and unresolved product rules are approved.
```

### Feature 005 — Ownership transfer

**Spec:** `docs/specs/005-ownership-transfer/spec.md`

**Key files:**
- Backend: `ownership/api/OwnershipTransferController.java`, initiation/acceptance services, `ownership/domain/OwnershipTransfer.java`, repository, membership service, audit service
- Migration: transfer state, recipient, expiry, and audit-related changes as approved
- Frontend: ownership settings and transfer acceptance pages
- Tests: concurrent transfers, duplicate acceptance, rollback, and last Owner invariant against PostgreSQL

**Prompt:**
```text
Plan SPEC 005-ownership-transfer. Do not code. Design explicit acceptance and a single
transaction that serializes company/membership changes, applies the approved old-Owner
role, records the transfer and audit event, and cannot leave a company without an Owner.
Describe database locking/constraints and real PostgreSQL concurrency tests. Flag any
unresolved business decisions and wait for approval.
```

### Feature 006 — Plans and entitlements

**Spec:** `docs/specs/006-plans-entitlements/spec.md`

**Key files:**
- Backend: `entitlement/api/PlanController.java`, `EntitlementService.java`, `TrialLifecycleService.java`, plan/subscription/entitlement entities and repositories
- Migrations: plan/subscription/entitlement schema and approved plan seed data
- Frontend: plan and usage pages/components, `apps/web/lib/api/entitlements.ts`
- Tests: trial boundary, entitlement denial, plan change, concurrent quota enforcement

**Prompt:**
```text
Plan SPEC 006-plans-entitlements. Do not code. Translate approved Trial/Starter/Team
rules into server-enforced entitlements and testable acceptance criteria. Specify when
trial starts/ends, quota concurrency behavior, plan-change behavior, seed/config strategy,
and UI states. Do not add payment processing unless the approved scope requires it.
Wait for approval.
```

### Feature 007 — Company deletion, purge, tombstone

**Spec:** `docs/specs/007-company-deletion/spec.md`

**Key files:**
- Backend: `deletion/api/CompanyDeletionController.java`, scheduling/cancellation services if approved, `CompanyPurgeService.java`, `PurgeScheduler.java`, lifecycle state, tombstone entity/repository
- Migrations: deletion state/deadline and tombstone schema as selected
- Frontend: danger-zone/delete confirmation and deletion status pages/components
- Runbook: create `docs/runbooks/company-purge.md`
- Tests: deadline, cancellation race, idempotent retry, partial failure, tombstone retention

**Prompt:**
```text
Plan SPEC 007-company-deletion. Do not code. Specify lifecycle states, grace-period
checks, cancellation races, idempotent purge steps, dependency cleanup, retention/tombstone
fields, authorization, observability, and operational safeguards. Destructive jobs must
recheck current state and deadline before deletion. Wait for approval.
```

---

## Step 7 — Test every feature and keep evidence

For each feature, run the commands that exist in your repository and record actual results. The exact commands depend on the selected build tool and package manager; update `CLAUDE.md` with the final commands once the skeleton is created.

Typical backend checks:

```bash
# Gradle example
./gradlew test
./gradlew build

# Maven alternative
./mvnw test
./mvnw verify
```

Typical frontend checks (run from `apps/web` and adjust to the scripts in `package.json`):

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

Also validate:
- Flyway migrations on a clean, disposable PostgreSQL database.
- API contract syntax and generated/client types if generation is used.
- Authorization and cross-tenant access denial.
- Concurrency behavior for single-use tokens, ownership transfer, quotas, and deletion.
- End-to-end flows through the actual approved BFF/direct API topology.

Do not run destructive tests against production. Use isolated test credentials and a dedicated test database.

### Prompt 7 — Independent review

Use the review prompt in `docs/runbooks/claude-code-prompts.md`. Tell the reviewer not to edit files. Fix findings in the implementation session, then rerun affected tests and request a second review when the risk is significant.

### Prompt 8 — Close a feature

```text
Compare this feature with its approved spec and all acceptance criteria. Produce a table
mapping each criterion to implementation and test evidence. List exact commands and
results, migration validation, review findings, unresolved gaps, and checks not run.
Update docs/implementation/feature-status.md. Do not claim completion when critical
criteria are unverified. Do not edit the approved spec to make an implementation appear
compliant; propose spec changes separately for approval.
```

---

## Step 8 — Add CI and deployment after the first vertical slice works

Create or update:
- `.github/workflows/ci.yml` (if using GitHub Actions)
- `Dockerfile` for the API and frontend, if deploying containers
- `infra/` deployment manifests or infrastructure-as-code for the chosen host
- `docs/runbooks/deployment.md`
- `docs/runbooks/backup-and-restore.md`
- `docs/runbooks/company-purge.md`

CI should build/test the backend, lint/typecheck/build the frontend, validate OpenAPI, and run relevant integration tests. Use managed secrets in CI/deployment. Never store actual credentials in `.env.example`, Git, or prompt files.

Do not add production deployment and automated purge execution before you have tested migration, backup/restore, authorization, and failure recovery procedures.

---

## Agent and skill usage

Existing agents live in `.claude/agents/`; existing skills live in `.claude/skills/<skill-name>/SKILL.md`.

Use the Product Analyst to clarify requirements, Software Architect to plan boundaries/transactions, API Contract Engineer for OpenAPI, Database Engineer for migrations, Backend Engineer for Spring Boot, Frontend Engineer for Next.js, Security Engineer for security review, Test Engineer for validation, DevOps Engineer for CI/operations, and Code Reviewer for independent review.

Do not start every agent on every task. Assign a single owner for shared files such as `contracts/openapi.yaml`, a Flyway migration, shared entities, and `SecurityConfig.java`. Parallelize only work that does not conflict.

Useful skills include `spec-authoring`, `spring-boot-feature`, `spring-security-auth`, `jpa-hibernate`, `flyway-migrations`, `api-contracts`, `multi-tenant-design`, `nextjs-feature`, `testing-strategy`, and `security-review`.

## Final checklist

- [ ] Architecture and session ADRs are accepted.
- [ ] Product policies are approved and specs are marked appropriately.
- [ ] Both applications run locally.
- [ ] Authentication works through the approved browser/API topology.
- [ ] Each feature has an approved plan and acceptance-criteria traceability.
- [ ] Flyway migrations are reviewed and tested against PostgreSQL.
- [ ] Tenant isolation, authorization, CSRF, token replay, and concurrency cases are tested.
- [ ] Frontend/backend API contracts are aligned.
- [ ] Independent review findings are resolved or explicitly accepted.
- [ ] CI and operational runbooks are in place before production deployment.
