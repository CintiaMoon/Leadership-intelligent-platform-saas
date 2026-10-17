# ADR-002: Build Tool and Version Baseline

- Status: Approved
- Date: 10/17/2026
- Decision owners: TBD
- Related: decision register D-04

## Context
`CLAUDE.md` mandates Maven and pins Spring Boot 4.1.1. The README and architecture docs previously left the build tool open. Builds must be reproducible locally and in CI.

## Decision
- Build tool: Maven.
- Backend: Java 21 and Spring Boot 4.1.1 (Spring Security, Spring Session JDBC, Hibernate, Flyway) are compatible. This verification has been done.
- Use the Maven wrapper so local and CI builds use the same Maven version.

## Alternatives considered
Gradle was an open option in the original README; `CLAUDE.md` selected Maven.

## Consequences
- The pinned versions are recorded in `pom.xml` and in `CLAUDE.md`; changing them needs a new ADR or an update to this one.
- If 4.1.1 is not supported or has a known security issue, pin a supported patch release and update `CLAUDE.md` and this ADR.

## Verification
- A clean build with the Maven wrapper on a machine with Java 21.
- CI uses the same wrapper and Java version.
