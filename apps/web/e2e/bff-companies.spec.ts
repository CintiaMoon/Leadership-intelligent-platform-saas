import type { Page } from "@playwright/test";

import { PASSWORD, createVerifiedAccount, expect, signIn, test, uniqueEmail } from "./support";

const ORIGIN = `http://localhost:${process.env.E2E_WEB_PORT ?? 3100}`;
const SYNTHETIC_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

/** Calls the BFF the way the browser client does: the session cookie from the page, the session-bound CSRF token. */
async function bff(page: Page, method: "POST" | "PATCH" | "DELETE", path: string, data?: object, origin = ORIGIN) {
  const csrf = (await (await page.request.get("/api/auth/csrf")).json()) as { token: string };
  return page.request.fetch(path, {
    method,
    headers: { origin, "x-csrf-token": csrf.token, ...(method === "DELETE" ? {} : { "content-type": "application/json" }) },
    data: method === "DELETE" ? undefined : (data ?? {}),
  });
}

test("the company operations work through the BFF against the real API, and nothing else does", async ({ page }) => {
  const email = uniqueEmail();
  await createVerifiedAccount(page, email);
  await signIn(page, email, PASSWORD);
  await expect(page).toHaveURL(/\/companies$/);

  // Create a company (POST /api/companies has no path segments).
  const created = await bff(page, "POST", "/api/companies", { name: "BFF Co" });
  expect(created.status()).toBe(201);
  const company = (await created.json()) as { id: string; role: string };
  expect(company.role).toBe("OWNER");

  // Invitations: create, resend, revoke.
  const invited = await bff(page, "POST", `/api/companies/${company.id}/invitations`, {
    email: uniqueEmail(),
    role: "VIEWER",
  });
  expect(invited.status()).toBe(201);
  const invitation = (await invited.json()) as { id: string };
  expect((await bff(page, "POST", `/api/companies/${company.id}/invitations/${invitation.id}/resend`)).status()).toBe(200);
  expect((await bff(page, "DELETE", `/api/companies/${company.id}/invitations/${invitation.id}`)).status()).toBe(204);
  expect((await bff(page, "DELETE", `/api/companies/${company.id}/invitations/${invitation.id}`)).status()).toBe(409);

  // An id that is not a membership of this company is the API's 404, not ours.
  expect((await bff(page, "POST", `/api/companies/${company.id}/members/${SYNTHETIC_ID}/suspend`)).status()).toBe(404);
  expect((await bff(page, "PATCH", `/api/companies/${company.id}/members/${SYNTHETIC_ID}`, { role: "AGENT" })).status()).toBe(404);
  expect((await bff(page, "DELETE", `/api/companies/${company.id}/members/${SYNTHETIC_ID}`)).status()).toBe(404);

  // The Owner cannot leave (the API's rule), and the answer comes back unchanged.
  expect((await bff(page, "POST", `/api/companies/${company.id}/leave`)).status()).toBeGreaterThanOrEqual(400);

  // Step-up: a wrong password is refused, the right one is recorded.
  expect((await bff(page, "POST", "/api/auth/step-up", { password: "not the password at all 1!" })).status()).toBe(401);
  expect((await bff(page, "POST", "/api/auth/step-up", { password: PASSWORD })).status()).toBe(204);

  // Near misses never reach the API.
  expect((await bff(page, "POST", "/api/companies/not-a-uuid/leave")).status()).toBe(404);
  expect((await bff(page, "POST", `/api/companies/${company.id}/members/${SYNTHETIC_ID}/promote`)).status()).toBe(404);
  expect((await bff(page, "PATCH", `/api/companies/${company.id}`, { name: "x" })).status()).toBe(404);
  expect((await page.request.get(`/api/companies/${company.id}/members`)).status()).toBeGreaterThanOrEqual(404);

  // Another origin is refused for every changing method.
  for (const [method, path] of [
    ["POST", "/api/companies"],
    ["PATCH", `/api/companies/${company.id}/members/${SYNTHETIC_ID}`],
    ["DELETE", `/api/companies/${company.id}/invitations/${SYNTHETIC_ID}`],
  ] as const) {
    expect((await bff(page, method, path, { name: "x" }, "https://evil.example")).status(), `${method} ${path}`).toBe(403);
  }
});

test("without a session the company operations are refused by the API", async ({ page }) => {
  await page.goto("/login");
  const response = await bff(page, "POST", "/api/companies", { name: "No Session Co" });
  expect([401, 403]).toContain(response.status());
});
