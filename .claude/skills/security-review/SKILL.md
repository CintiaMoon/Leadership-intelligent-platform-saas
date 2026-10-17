# Security Review

## When to use
Use for design reviews, pull-request reviews, pre-release reviews, and security-sensitive changes.

## Procedure
1. Read the threat model, approved spec, invariants, and complete diff.
2. Trace all entry points and sensitive data flows.
3. Check authentication and server-side authorization.
4. Check tenant scoping and related-record ownership.
5. Check CSRF, session, token, and secret handling.
6. Check rate limits, lockout, abuse cases, and enumeration.
7. Check transaction boundaries and concurrency races.
8. Check deletion, retention, audit, and external side effects.
9. Record findings with severity, evidence, reproduction, and mitigation.
10. Re-test corrected findings.

## Reporting
Distinguish confirmed vulnerabilities from potential concerns. Do not claim security certification. Do not modify code during the initial review unless explicitly asked.
