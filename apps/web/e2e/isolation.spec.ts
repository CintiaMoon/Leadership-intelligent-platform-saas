import { randomUUID } from "node:crypto";

import type { Browser, Page } from "@playwright/test";

import { PASSWORD, apiLogin, createVerifiedAccount, expect, signIn, test, uniqueEmail } from "./support";

// Two accounts are built before anything is checked.
test.describe.configure({ timeout: 240_000 });

async function ownerWithCompany(page: Page, name: string) {
  const email = uniqueEmail();
  await createVerifiedAccount(page, email, PASSWORD, name);
  const api = await apiLogin(email);
  const companyName = `Co ${randomUUID().slice(0, 8)}`;
  const company = (await (await api.send("POST", "/api/v1/companies", { name: companyName })).json()) as { id: string };
  await api.dispose();
  return { email, name, companyName, companyId: company.id };
}

async function signedIn(browser: Browser, email: string) {
  const context = await browser.newContext({ extraHTTPHeaders: { "x-forwarded-for": `198.18.${1 + Math.floor(Math.random() * 254)}.${1 + Math.floor(Math.random() * 254)}` } });
  const page = await context.newPage();
  await signIn(page, email, PASSWORD);
  await expect(page).toHaveURL(/\/companies$/);
  return { context, page };
}

test("two people using the app at once never see each other's pages, and a page is not kept after sign-out", async ({ page, browser }) => {
  const a = await ownerWithCompany(page, "Alice Aardvark");
  const b = await ownerWithCompany(page, "Bruno Badger");
  const sessionA = await signedIn(browser, a.email);
  const sessionB = await signedIn(browser, b.email);

  const read = async (who: { page: Page }, path: string) => {
    const response = await who.page.request.get(path);
    expect(response.status()).toBe(200);
    expect(response.headers()["cache-control"]).toContain("no-store");
    return response.text();
  };
  // Interleaved and simultaneous requests for the same pages from different people.
  const results = await Promise.all(
    Array.from({ length: 12 }, (_, i) => {
      const who = i % 2 === 0 ? sessionA : sessionB;
      const path = i % 4 < 2 ? "/companies" : "/account";
      return read(who, path).then((html) => ({ owner: i % 2 === 0 ? "A" : "B", path, html }));
    }),
  );
  for (const result of results) {
    const own = result.owner === "A" ? a : b;
    const other = result.owner === "A" ? b : a;
    expect(result.html, `${result.owner} ${result.path}`).toContain(result.path === "/companies" ? own.companyName : own.name);
    expect(result.html, `${result.owner} ${result.path}`).not.toContain(other.companyName);
    expect(result.html, `${result.owner} ${result.path}`).not.toContain(other.name);
    expect(result.html, `${result.owner} ${result.path}`).not.toContain(other.email);
  }

  // Another person's company, and an id that matches nothing, give the same page.
  const foreign = await sessionA.page.request.get(`/companies/${b.companyId}`);
  const missing = await sessionA.page.request.get(`/companies/${randomUUID()}`);
  expect(foreign.status()).toBe(missing.status());
  const text = (html: string) => html.replace(/<script[\s\S]*?<\/script>/g, "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ");
  expect(text(await foreign.text())).toBe(text(await missing.text()));
  expect(await foreign.text()).not.toContain(b.companyName);

  // After signing out, the Back button does not bring the person's companies back: the page is read again, without a session.
  await sessionA.page.goto("/companies");
  await expect(sessionA.page.getByText(a.companyName)).toBeVisible();
  await sessionA.page.getByRole("link", { name: a.name }).click();
  await expect(sessionA.page).toHaveURL(/\/account$/);
  await sessionA.page.getByRole("button", { name: "Sign out" }).click();
  await expect(sessionA.page).toHaveURL(/\/login$/);
  await sessionA.page.goBack();
  await expect(sessionA.page).toHaveURL(/\/login/);
  await expect(sessionA.page.getByText(a.companyName)).toHaveCount(0);

  await sessionA.context.close();
  await sessionB.context.close();
});
