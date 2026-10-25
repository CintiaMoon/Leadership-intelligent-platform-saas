import { randomUUID } from "node:crypto";

import AxeBuilder from "@axe-core/playwright";
import { expect, request as playwrightRequest, test as base, type Page } from "@playwright/test";

const MAILPIT = process.env.E2E_MAILPIT_URL ?? "http://localhost:8025";

export const PASSWORD = "correct horse battery staple 7!";
export const NEW_PASSWORD = "another long passphrase 42?";

/**
 * Each test acts as a different client address. The BFF forwards X-Forwarded-For as the platform edge would, and
 * the API's per-address limits would otherwise be shared by every test.
 */
export const test = base.extend<{ cspViolations: string[] }>({
  extraHTTPHeaders: async ({}, provide) => {
    const octet = () => 1 + Math.floor(Math.random() * 254);
    await provide({ "x-forwarded-for": `198.18.${octet()}.${octet()}` });
  },
  // Every test doubles as a Content Security Policy check: Chromium reports each blocked script or style in the console.
  cspViolations: [
    async ({ page }, provide) => {
      const violations: string[] = [];
      page.on("console", (message) => {
        if (/content security policy/i.test(message.text())) {
          violations.push(message.text());
        }
      });
      await provide(violations);
      expect(violations, "Content Security Policy violations").toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

/** The form's error notice. Next.js keeps its own role="alert" route announcer on every page. */
export function alertIn(page: Page) {
  return page.locator('[role="alert"]:not(#__next-route-announcer__)');
}

export function uniqueEmail(): string {
  return `e2e.${randomUUID()}@example.com`;
}

type MailSummary = { ID: string; Subject: string };

/** The newest message to this address whose subject contains the text, waiting for the outbox worker to send it. */
export async function waitForMail(to: string, subject: string): Promise<string> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const search = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`);
    const { messages } = (await search.json()) as { messages: MailSummary[] };
    const match = messages.find((message) => message.Subject.includes(subject));
    if (match) {
      const detail = await fetch(`${MAILPIT}/api/v1/message/${match.ID}`);
      return ((await detail.json()) as { Text: string }).Text;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`No mail with subject "${subject}" arrived for the test address`);
}

/** The link in a mail, such as /verify-email#token=... */
export function linkIn(text: string, path: string): string {
  const match = text.match(new RegExp(`https?://[^\\s]+/${path}#token=[A-Za-z0-9_-]+`));
  if (!match) {
    throw new Error(`No ${path} link in the mail`);
  }
  return match[0];
}

export async function signUp(page: Page, email: string, password = PASSWORD, name = "Alex Morgan") {
  await page.goto("/signup");
  await page.getByLabel("Full name").fill(name);
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/check-email$/);
}

/** Signs up and confirms the address through the emailed link. */
export async function createVerifiedAccount(page: Page, email: string, password = PASSWORD) {
  await signUp(page, email, password);
  const link = linkIn(await waitForMail(email, "Verify your email"), "verify-email");
  await page.goto(link);
  await expect(page.getByText("Your email address is verified.")).toBeVisible();
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

const WEB_URL = `http://localhost:${process.env.E2E_WEB_PORT ?? 3100}`;
const API_URL = `http://localhost:${process.env.E2E_API_PORT ?? 18090}`;
// The throwaway credential of playwright.config.ts; it protects nothing outside the E2E database.
const BFF_CREDENTIAL = "e2e-only-bff-credential-".repeat(3);

/**
 * Creates a company as an existing, verified account and invites an address to it, calling the API the way a client
 * with no screen yet would (the member and invitation pages are a later frontend slice). Returns the company name,
 * which the invitation email carries. Uses the default (non-prod) cookie settings.
 */
export async function inviteByApi(
  ownerEmail: string,
  inviteeEmail: string,
  role: "ADMIN" | "AGENT" | "VIEWER" = "VIEWER",
): Promise<string> {
  const octet = () => 1 + Math.floor(Math.random() * 254);
  const api = await playwrightRequest.newContext({
    baseURL: API_URL,
    extraHTTPHeaders: {
      "X-BFF-Credential": BFF_CREDENTIAL,
      Origin: WEB_URL,
      "X-Forwarded-For": `198.19.${octet()}.${octet()}`,
    },
  });
  try {
    const csrf = async () => ((await (await api.get("/api/v1/auth/csrf")).json()) as { token: string }).token;
    const send = async (path: string, data: object) => {
      const response = await api.post(path, { data, headers: { "X-CSRF-TOKEN": await csrf() } });
      expect(response.ok(), `${path} answered ${response.status()}`).toBe(true);
      return (await response.json()) as { id?: string };
    };
    await send("/api/v1/auth/login", { email: ownerEmail, password: PASSWORD });
    const name = `Acme ${randomUUID().slice(0, 8)}`;
    const company = await send("/api/v1/companies", { name });
    await send(`/api/v1/companies/${company.id}/invitations`, { email: inviteeEmail, role });
    return name;
  } finally {
    await api.dispose();
  }
}

/** Fails on serious or critical accessibility violations. An automated scan does not prove a page is accessible. */
export async function expectNoSeriousA11yViolations(page: Page) {
  const { violations } = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag22aa"]).analyze();
  const serious = violations.filter((v) => v.impact === "serious" || v.impact === "critical");
  expect(serious.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}
