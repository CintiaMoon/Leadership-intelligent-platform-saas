# Claude Code Prompts — Spring Boot Spec-Driven Workflow

Use these prompts only after the relevant specs/ADRs are present. Replace bracketed placeholders with repository paths or feature IDs.

## 1. Analyze and plan; do not code

```text
Read CLAUDE.md, the approved feature spec [SPEC-ID], architecture invariants, threat model, relevant ADRs, and API contract guidelines.
Do not implement code yet.
Produce a plan for Next.js + Spring Boot (Java 21) + PostgreSQL + JPA/Hibernate + Flyway.
Include acceptance-criteria traceability, endpoint/DTO changes, auth/CSRF/authorization rules, transaction and concurrency strategy, migration SQL outline, tests, frontend work, risks, and unresolved questions.
Do not invent product decisions. Wait for approval.
```

## 2. Implement backend slice

```text
Implement only the approved backend portion of [SPEC-ID] and its approved OpenAPI contract.
Use Java 21 and the repository's pinned Spring Boot/build tool. Keep controllers thin, business rules in application/domain services, DTOs at the API boundary, explicit transaction boundaries, Spring Security authorization, and Flyway for schema changes.
Do not expose JPA entities. Do not perform network calls inside DB transactions. Add unit and PostgreSQL integration tests, including negative/security/concurrency cases appropriate to the spec.
Run relevant checks and report exact commands and results, changed files, migrations, deviations, and remaining risks. Do not claim unrun checks passed.
```

## 3. Implement frontend slice

```text
Implement the Next.js UI for [SPEC-ID] using the approved OpenAPI contract and browser/API session ADR.
Do not duplicate business authorization or create a second session system. Follow the approved CSRF flow. Cover loading, success, validation, unauthorized, forbidden, conflict, and network errors. Add accessible UI and relevant tests. Run frontend checks and report actual results.
```

## 4. Independent review; do not edit

```text
Review the implementation for [SPEC-ID] against the approved spec, ADRs, OpenAPI contract, invariants, and threat model. Do not modify files.
Find missing acceptance criteria, tenant leaks, authorization gaps, transaction/race bugs, unsafe JPA behavior, migration risks, token replay, CSRF/session flaws, contract mismatches, and missing tests. Report severity, evidence, requirement affected, and correction. Distinguish confirmed defects from questions.
```

## 5. Close feature

```text
Compare the implementation and test evidence with every acceptance criterion in [SPEC-ID]. Produce a traceability table with criterion, code/test evidence, status, and gaps. List actual commands/results, migration status, security review findings, deviations, and unrun checks. Do not mark the feature complete if critical criteria are unverified.
```
