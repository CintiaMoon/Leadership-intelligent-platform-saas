# Next.js Frontend Feature

## When to use
Use for frontend pages, forms, components, API client integration, and browser flows.

## Procedure
1. Read the approved feature spec, OpenAPI contract, and frontend architecture decision.
2. Build the UI with Next.js App Router, TypeScript, and Tailwind CSS.
3. Use shared typed API client/types aligned with OpenAPI where available.
4. Handle loading, success, validation, unauthorized, forbidden, conflict, and network failure states.
5. Do not duplicate business authorization or assume hiding a control protects the API.
6. Do not store session secrets in localStorage or expose credentials to client-side JavaScript.
7. Follow the approved BFF/direct API and CSRF flow; do not invent a second session system.
8. Add accessible form validation and frontend tests; add E2E coverage for critical journeys.
9. Run frontend tests, typecheck, lint, and production build.
