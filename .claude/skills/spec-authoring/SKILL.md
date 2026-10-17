# Spec Authoring

## When to use
Use when translating a feature brief into an approved, testable specification.

## Procedure
1. Read CLAUDE.md, the requirements baseline, relevant ADRs, and glossary.
2. Identify actors, preconditions, main flows, failure flows, and state transitions.
3. Assign stable IDs to business rules and acceptance criteria.
4. Identify authentication, authorization, tenant isolation, concurrency, and abuse cases.
5. Separate confirmed requirements from implementation proposals.
6. Record open questions in an assumptions/decisions register.
7. Trace requirements to acceptance criteria and planned tests.
8. Request review; do not mark the spec Approved without explicit approval.

## Required output
A spec with objective, actors, rules, flows, data, interfaces, security, acceptance criteria, tests, operations, and open decisions.

## Completion checklist
- All requirements are traceable.
- Acceptance criteria are observable and testable.
- Failure and concurrent paths are considered.
- No undocumented business policy is presented as fact.
