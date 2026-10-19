# Web app (Next.js, the BFF)

The only public service. It renders the authentication pages and proxies a fixed list of operations to the
Spring Boot API (`/api/auth/*`, see `src/lib/server/bff.ts`). It has no session store of its own: the session
cookie belongs to the API.

Design source: `docs/design/auth` (sign in, sign up, profile mockups). Decisions: D-92 to D-94 in
`docs/architecture/decision-register.md`.

## Commands
```bash
npm ci
npm run dev            # needs the API running; see .env.example for API_BASE_URL and BFF_CREDENTIAL
npm run lint
npm run typecheck
npm test               # Vitest
npm run build
npm run gen:api        # regenerate src/lib/api/schema.d.ts from contracts/openapi.yaml
```

## Local development
Start PostgreSQL and Mailpit with `infra/local/compose.yaml`, run the API (`services/api`), then `npm run dev`.
Over plain HTTP the `__Host-` cookie prefix cannot be used, so set `CSRF_COOKIE_NAME`, `SESSION_COOKIE_NAME`,
and the `*_SECURE=false` values on both sides as described in `.env.example`.

## End-to-end tests (Playwright)
They run the built web app, the API jar, PostgreSQL, and Mailpit. The credentials in `playwright.config.ts`
are fixed test values for a throwaway database.
```bash
# once
docker run -d --name e2e-postgres -e POSTGRES_DB=saas_e2e -e POSTGRES_USER=saas \
  -e POSTGRES_PASSWORD=e2e-local-only -p 127.0.0.1:55432:5432 postgres:17.6
docker run -d --name e2e-mailpit -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 axllent/mailpit:v1.31.6
npx playwright install chromium

# each run
(cd ../../services/api && ./mvnw -DskipTests package)
npm run build
npm run e2e
```
Ports can be changed with `E2E_WEB_PORT`, `E2E_API_PORT`, `E2E_DB_PORT`, `E2E_SMTP_PORT`, and `E2E_MAILPIT_URL`.
CI runs the same steps in the `e2e` job.
