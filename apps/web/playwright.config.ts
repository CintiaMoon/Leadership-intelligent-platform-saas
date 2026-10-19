import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests run the real stack: the built Next.js app (the BFF), the Spring Boot API from
 * services/api/target, PostgreSQL, and Mailpit as the mail server. Start PostgreSQL and Mailpit first (CI uses
 * service containers; locally see the README), build the API jar and the web app, then run `npm run e2e`.
 *
 * The credentials below are fixed, test-only values for a throwaway database; they protect nothing.
 */
const WEB_PORT = Number(process.env.E2E_WEB_PORT ?? 3100);
const API_PORT = Number(process.env.E2E_API_PORT ?? 18090);
const DB_PORT = process.env.E2E_DB_PORT ?? "55432";
const SMTP_PORT = process.env.E2E_SMTP_PORT ?? "1025";
const WEB_URL = `http://localhost:${WEB_PORT}`;

const BFF_CREDENTIAL = "e2e-only-bff-credential-".repeat(3);
const DB_PASSWORD = process.env.E2E_DB_PASSWORD ?? "e2e-local-only";

// Cookie names match on both sides. The default run uses plain names and non-Secure cookies. E2E_PROD_COOKIES=true
// leaves both sides on their production defaults (__Host- names, Secure): Chromium accepts Secure cookies on
// localhost, so the whole suite then proves the production cookie settings work end to end.
const PROD_COOKIES = process.env.E2E_PROD_COOKIES === "true";
// Named explicitly because a developer's .env (which Next.js loads) may carry the plain local names.
const COOKIES = PROD_COOKIES ? { CSRF_COOKIE_NAME: "__Host-csrf", SESSION_COOKIE_NAME: "__Host-session" } : { CSRF_COOKIE_NAME: "csrf", SESSION_COOKIE_NAME: "session" };
const COOKIE_SECURITY = PROD_COOKIES ? { CSRF_COOKIE_SECURE: "true", SESSION_COOKIE_SECURE: "true" } : { CSRF_COOKIE_SECURE: "false", SESSION_COOKIE_SECURE: "false" };

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  timeout: 60_000,
  use: {
    baseURL: WEB_URL,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "java -jar ../../services/api/target/api-0.0.1-SNAPSHOT.jar",
      url: `http://localhost:${API_PORT}/actuator/health`,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      env: {
        SERVER_PORT: String(API_PORT),
        DB_URL: `jdbc:postgresql://localhost:${DB_PORT}/saas_e2e`,
        DB_USERNAME: "saas",
        DB_PASSWORD,
        BFF_CREDENTIALS: BFF_CREDENTIAL,
        RATE_LIMIT_HMAC_KEY: "e2e-only-rate-limit-key-".repeat(3),
        CSRF_HMAC_KEY: "e2e-only-csrf-signing-key-".repeat(3),
        PUBLIC_BASE_URL: WEB_URL,
        CSRF_ALLOWED_ORIGINS: WEB_URL,
        ...COOKIE_SECURITY,
        SMTP_HOST: "localhost",
        SMTP_PORT,
        ...COOKIES,
      },
    },
    {
      command: `npm run start -- --port ${WEB_PORT}`,
      url: `${WEB_URL}/login`,
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
      env: {
        API_BASE_URL: `http://localhost:${API_PORT}`,
        BFF_CREDENTIAL,
        PUBLIC_BASE_URL: WEB_URL,
        ...COOKIES,
      },
    },
  ],
});
