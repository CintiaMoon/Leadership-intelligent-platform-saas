import type { Page } from "@playwright/test";

import {
  PASSWORD,
  createVerifiedAccount,
  expect,
  expectNoSeriousA11yViolations,
  inviteByApi,
  linkIn,
  signIn,
  test,
  uniqueEmail,
  waitForMail,
} from "./support";

const SHOTS = "test-results/f1-2";

async function createCompany(page: Page, name: string) {
  await page.getByLabel("Company name").fill(name);
  await page.getByRole("button", { name: "Create company" }).click();
}

async function shots(page: Page, name: string) {
  for (const [label, width, height] of [["360", 360, 780], ["768", 768, 900], ["1280", 1280, 800]] as const) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${SHOTS}/${name}-${label}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 1280, height: 720 });
}

test("sign in lands on My companies; creating a company opens it and the list shows it", async ({ page }) => {
  const email = uniqueEmail();
  await createVerifiedAccount(page, email);
  await signIn(page, email, PASSWORD);

  await expect(page).toHaveURL(/\/companies$/);
  await expect(page.getByRole("heading", { level: 1, name: "My companies" })).toBeVisible();
  await expect(page.getByText("You do not belong to any company yet.")).toBeVisible();
  await expectNoSeriousA11yViolations(page);
  await shots(page, "companies-empty");

  await createCompany(page, "Acme Labs");

  await expect(page).toHaveURL(/\/companies\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Acme Labs");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Owner");
  await expect(page.getByText("Company home is coming")).toBeVisible();
  await expect(page.getByText("Transfer ownership first.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Leave company" })).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);
  await shots(page, "company-owner");

  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "My companies" }).click();
  await expect(page).toHaveURL(/\/companies$/);
  await expect(page.getByRole("link", { name: "Open Acme Labs" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
  await shots(page, "companies-list");

  await page.getByRole("link", { name: "Open Acme Labs" }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Acme Labs");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
});

test("a company name is shown as text, and a bad name is refused with a field error", async ({ page }) => {
  const email = uniqueEmail();
  await createVerifiedAccount(page, email);
  await signIn(page, email, PASSWORD);
  await expect(page).toHaveURL(/\/companies$/);

  await createCompany(page, "Right‮to-left");
  await expect(page.getByLabel("Company name")).toHaveAttribute("aria-invalid", "true");
  await expect(page).toHaveURL(/\/companies$/);

  const markup = '<b>bold</b> & "quotes"';
  await page.getByLabel("Company name").fill(markup);
  await page.getByRole("button", { name: "Create company" }).click();
  await expect(page).toHaveURL(/\/companies\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText(markup);
  expect(await page.locator("h1 b").count()).toBe(0);
});

test("a member who is not the Owner sees their role and can leave after confirming", async ({ page, context }) => {
  const invitee = uniqueEmail();
  await createVerifiedAccount(page, invitee);
  const owner = uniqueEmail();
  await createVerifiedAccount(page, owner);
  const company = await inviteByApi(owner, invitee);
  const link = linkIn(await waitForMail(invitee, "You have been invited to a workspace"), "invitations/accept");
  await context.clearCookies();

  await page.goto(link);
  await page.getByRole("link", { name: "Sign in" }).click();
  await page.getByLabel("Work email").fill(invitee);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/invitations\/accept$/);
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("status")).toHaveText(`You joined ${company} as Viewer.`);

  await page.getByRole("link", { name: "Go to my companies" }).click();
  await expect(page).toHaveURL(/\/companies$/);
  await expect(page.getByRole("link", { name: `Open ${company}` })).toBeVisible();
  await page.getByRole("link", { name: `Open ${company}` }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Viewer");
  await expect(page.getByText("You are a viewer of this company.")).toBeVisible();
  await shots(page, "company-viewer");

  await page.getByRole("button", { name: "Leave company" }).click();
  const dialog = page.getByRole("dialog", { name: `Leave ${company}?` });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  await expectNoSeriousA11yViolations(page);
  await page.setViewportSize({ width: 360, height: 780 });
  await page.screenshot({ path: `${SHOTS}/leave-dialog-360.png` });
  await page.setViewportSize({ width: 1280, height: 720 });

  // Escape cancels and focus goes back to the button that opened it.
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("button", { name: "Leave company" })).toBeFocused();

  await page.getByRole("button", { name: "Leave company" }).click();
  await dialog.getByRole("button", { name: "Leave company" }).click();
  await expect(page).toHaveURL(/\/companies$/);
  await expect(page.getByText("You do not belong to any company yet.")).toBeVisible();
  await expect(page.getByRole("link", { name: `Open ${company}` })).toHaveCount(0);
});

test("a company that is not yours, or not an id at all, shows the same calm page; signed out goes to sign in", async ({
  page,
  context,
}) => {
  const owner = uniqueEmail();
  await createVerifiedAccount(page, owner);
  await signIn(page, owner, PASSWORD);
  await createCompany(page, "Private Co");
  await expect(page).toHaveURL(/\/companies\/[0-9a-f-]{36}$/);
  const companyUrl = page.url();
  await context.clearCookies();

  await page.goto(companyUrl);
  await expect(page).toHaveURL(/\/login$/);

  const stranger = uniqueEmail();
  await createVerifiedAccount(page, stranger);
  await signIn(page, stranger, PASSWORD);
  await expect(page).toHaveURL(/\/companies$/);

  for (const path of [new URL(companyUrl).pathname, "/companies/not-a-uuid", "/companies/a%3Fb%3Dc", "/companies/..%2F..%2Fapi", "/companies/11111111-1111-4111-8111-111111111111"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: "This page is not available." })).toBeVisible();
    await expect(page.getByText("Private Co")).toHaveCount(0);
  }
  await expectNoSeriousA11yViolations(page);
});
