import type { Page } from "@playwright/test";

import {
  PASSWORD,
  createVerifiedAccount,
  expect,
  expectNoSeriousA11yViolations,
  makeTeam,
  signIn as signInRaw,
  test,
  uniqueEmail,
  waitForMail,
} from "./support";

const SHOTS = "test-results/own-5";

// Each test first builds a team of real accounts (sign-up, verification mail, invitation mail), which takes a while.
test.describe.configure({ timeout: 300_000 });

function notice(page: Page) {
  return page.getByRole("status").filter({ hasText: /\S/ });
}

async function shots(page: Page, name: string) {
  for (const [label, width, height] of [["360", 360, 780], ["768", 768, 900], ["1280", 1280, 800]] as const) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${SHOTS}/${name}-${label}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 1280, height: 720 });
}

/** Signs in and waits until the companies list is showing, so the next navigation does not interrupt the sign-in. */
async function signIn(page: Page, email: string, password: string) {
  await signInRaw(page, email, password);
  await expect(page).toHaveURL(/\/companies$/);
}

async function openCompany(page: Page, companyName: string) {
  await page.getByRole("link", { name: `Open ${companyName}` }).click();
}

test("the Owner asks an Admin, the Admin accepts and becomes the Owner, who then asks an Agent", async ({ page, context }) => {
  const team = await makeTeam(page, ["ADMIN", "AGENT", "VIEWER"]);
  const admin = team.members.ADMIN!;
  const agent = team.members.AGENT!;
  const viewer = team.members.VIEWER!;
  await context.clearCookies();

  // The Owner asks Bo. Only active members other than the Owner are offered.
  await signIn(page, team.ownerEmail, PASSWORD);
  await openCompany(page, team.companyName);
  await page.getByRole("link", { name: "Ownership" }).click();
  await expect(page).toHaveURL(new RegExp(`/companies/${team.companyId}/ownership$`));
  await expect(page.getByRole("link", { name: "Ownership" })).toHaveAttribute("aria-current", "page");
  await expect(page.getByText("You will become an Admin. The new Owner can remove you.")).toBeVisible();
  const select = page.getByRole("combobox", { name: "New Owner" });
  await expect(select.locator("option")).toHaveText(["Choose a member", "Bo Admin (Admin)", "Cy Agent (Agent)", "Di Viewer (Viewer)"]);
  await expectNoSeriousA11yViolations(page);
  await shots(page, "owner-form");

  await page.getByRole("button", { name: "Transfer ownership" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Choose the member" })).toBeVisible();
  await select.selectOption({ label: "Bo Admin (Admin)" });
  await page.getByRole("button", { name: "Transfer ownership" }).click();
  await expect(notice(page)).toHaveText("Bo Admin was asked to become the Owner. They have 7 days to accept.");
  await expect(page.getByRole("heading", { name: "Waiting for Bo Admin" })).toBeVisible();
  await expect(page.getByText(/The request expires .* UTC\./)).toBeVisible();
  await expect(page.getByRole("combobox")).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);
  await shots(page, "owner-pending");

  // The recipient was emailed a link without a token.
  const mail = await waitForMail(admin.email, "You were asked to become an Owner");
  expect(mail).toContain(`/companies/${team.companyId}/ownership`);
  expect(mail).not.toContain("token");

  // Somebody else in the company sees nothing about it.
  await context.clearCookies();
  await signIn(page, viewer.email, PASSWORD);
  await openCompany(page, team.companyName);
  await expect(page.getByText("Company home is coming")).toBeVisible();
  await expect(page.getByRole("region", { name: "Ownership request" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Ownership" })).toHaveCount(0);
  await page.goto(`/companies/${team.companyId}/ownership`);
  await expect(page.getByRole("heading", { name: "Nothing is waiting for you" })).toBeVisible();
  await expect(page.getByText("Bo Admin")).toHaveCount(0);

  // The recipient sees the banner on Members, reviews the request and accepts.
  await context.clearCookies();
  await signIn(page, admin.email, PASSWORD);
  await openCompany(page, team.companyName);
  await expect(page).toHaveURL(/\/members$/);
  const banner = page.getByRole("region", { name: "Ownership request" });
  await expect(banner).toContainText("Olivia Owner asked you to become the Owner of this company.");
  await expectNoSeriousA11yViolations(page);
  await banner.getByRole("link", { name: "Review the request" }).click();
  await expect(page.getByRole("heading", { name: "Olivia Owner asked you to become the Owner" })).toBeVisible();
  await expect(page.getByText(/you become the Owner of .* and Olivia Owner becomes an Admin/)).toBeVisible();
  await expect(page.getByRole("button", { name: /decline|reject/i })).toHaveCount(0);
  await expectNoSeriousA11yViolations(page);
  await shots(page, "recipient-request");

  await page.getByRole("button", { name: "Accept ownership" }).click();
  await expect(notice(page)).toHaveText(`You are now the Owner of ${team.companyName}.`);
  await expect(page.getByRole("heading", { name: "Transfer ownership" })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "New Owner" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
  await shots(page, "recipient-accepted");

  // Both people were told, and the roles really changed: the new Owner has Owner controls on Members, the old one does not.
  await waitForMail(admin.email, "Ownership of a company was transferred");
  await waitForMail(team.ownerEmail, "Ownership of a company was transferred");
  await page.getByRole("link", { name: "Members" }).click();
  await expect(page.getByRole("heading", { name: "Members (4)" })).toBeVisible();
  await expect(page.getByText("Transfer ownership first.")).toBeVisible();
  await expect(page.getByRole("listitem").filter({ hasText: "Olivia Owner" }).getByRole("combobox", { name: "Role of Olivia Owner" })).toHaveValue("ADMIN");

  await context.clearCookies();
  await signIn(page, team.ownerEmail, PASSWORD);
  await openCompany(page, team.companyName);
  await expect(page.getByText("Transfer ownership first.")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Ownership" })).toHaveCount(0);
  await page.goto(`/companies/${team.companyId}/ownership`);
  await expect(page.getByRole("heading", { name: "Nothing is waiting for you" })).toBeVisible();

  // Bo, the new Owner, asks Cy, an Agent. An Agent has no tabs, but the banner on the company page leads in.
  await context.clearCookies();
  await signIn(page, admin.email, PASSWORD);
  await page.goto(`/companies/${team.companyId}/ownership`);
  await page.getByRole("combobox", { name: "New Owner" }).selectOption({ label: "Cy Agent (Agent)" });
  await page.getByRole("button", { name: "Transfer ownership" }).click();
  await expect(notice(page)).toHaveText("Cy Agent was asked to become the Owner. They have 7 days to accept.");

  await context.clearCookies();
  await signIn(page, agent.email, PASSWORD);
  await openCompany(page, team.companyName);
  await expect(page.getByText("Company home is coming")).toBeVisible();
  await expect(page.getByRole("link", { name: "Members" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Ownership" })).toBeVisible();
  const agentBanner = page.getByRole("region", { name: "Ownership request" });
  await expect(agentBanner).toContainText("Bo Admin asked you to become the Owner of this company.");
  await expectNoSeriousA11yViolations(page);
  await shots(page, "agent-banner");
  await agentBanner.getByRole("link", { name: "Review the request" }).click();
  await page.getByRole("button", { name: "Accept ownership" }).click();
  await expect(notice(page)).toHaveText(`You are now the Owner of ${team.companyName}.`);
  await page.getByRole("link", { name: "Members" }).click();
  await expect(page.getByRole("heading", { name: "Members (4)" })).toBeVisible();
});

test("the Owner cancels, a stale page is explained, and outsiders find nothing", async ({ page, context, browser }) => {
  const team = await makeTeam(page, ["ADMIN"]);
  const admin = team.members.ADMIN!;
  await context.clearCookies();

  await signIn(page, team.ownerEmail, PASSWORD);
  await page.goto(`/companies/${team.companyId}/ownership`);
  await page.getByRole("combobox", { name: "New Owner" }).selectOption({ label: "Bo Admin (Admin)" });
  await page.getByRole("button", { name: "Transfer ownership" }).click();
  await expect(page.getByRole("heading", { name: "Waiting for Bo Admin" })).toBeVisible();

  // The recipient opens the request in a second browser and leaves the page open.
  const other = await browser.newContext();
  const recipientPage = await other.newPage();
  await signIn(recipientPage, admin.email, PASSWORD);
  await recipientPage.goto(`/companies/${team.companyId}/ownership`);
  await expect(recipientPage.getByRole("button", { name: "Accept ownership" })).toBeVisible();

  // The Owner cancels, after a confirmation that names the person. Escape leaves it.
  await page.getByRole("button", { name: "Cancel the request" }).click();
  const dialog = page.getByRole("dialog", { name: "Cancel the request to Bo Admin?" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Cancel", exact: true })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("heading", { name: "Waiting for Bo Admin" })).toBeVisible();
  await page.getByRole("button", { name: "Cancel the request" }).click();
  await dialog.getByRole("button", { name: "Cancel the request" }).click();
  await expect(notice(page)).toHaveText("The request to Bo Admin was cancelled.");
  await expect(page.getByRole("combobox", { name: "New Owner" })).toBeVisible();
  await waitForMail(admin.email, "An ownership transfer was cancelled");

  // The recipient's old page: accepting is refused, explained, and the page is read again.
  await recipientPage.getByRole("button", { name: "Accept ownership" }).click();
  await expect(notice(recipientPage)).toHaveText("This transfer changed. The page was refreshed.");
  await expect(recipientPage.getByRole("heading", { name: "Nothing is waiting for you" })).toBeVisible();
  await other.close();

  // Somebody who is not a member of this company gets the same page as for a company that does not exist.
  await context.clearCookies();
  const stranger = await browser.newContext();
  const strangerPage = await stranger.newPage();
  const strangerEmail = uniqueEmail();
  await createVerifiedAccount(strangerPage, strangerEmail);
  await signIn(strangerPage, strangerEmail, PASSWORD);
  await strangerPage.goto(`/companies/${team.companyId}/ownership`);
  await expect(strangerPage.getByRole("heading", { name: "This page is not available." })).toBeVisible();
  await expect(strangerPage.getByText("Bo Admin")).toHaveCount(0);
  await expect(strangerPage.getByText(team.companyName)).toHaveCount(0);
  await stranger.close();
});

test("ownership can be asked, cancelled and accepted with the keyboard alone", async ({ page, context, browser }) => {
  const team = await makeTeam(page, ["ADMIN"]);
  const admin = team.members.ADMIN!;
  await context.clearCookies();

  await signIn(page, team.ownerEmail, PASSWORD);
  await page.goto(`/companies/${team.companyId}/ownership`);
  const select = page.getByRole("combobox", { name: "New Owner" });
  await select.focus();
  await page.keyboard.press("ArrowDown");
  await expect(select).toHaveValue(/.+/);
  const outline = await select.evaluate((el) => getComputedStyle(el).outlineStyle);
  expect(outline).not.toBe("none");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Transfer ownership" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Waiting for Bo Admin" })).toBeVisible();
  // After the form is replaced, focus is not lost to the page: it lands on the message.
  await expect(notice(page)).toBeFocused();

  // Cancel by keyboard: Cancel has the first focus in the dialog, Tab reaches the confirm button, Escape returns focus.
  const cancel = page.getByRole("button", { name: "Cancel the request" });
  await cancel.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Cancel the request to Bo Admin?" });
  await expect(dialog.getByRole("button", { name: "Cancel", exact: true })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(dialog.getByRole("button", { name: "Cancel the request" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(cancel).toBeFocused();

  // The recipient accepts with Enter on the focused button.
  const other = await browser.newContext();
  const recipientPage = await other.newPage();
  await signIn(recipientPage, admin.email, PASSWORD);
  await recipientPage.goto(`/companies/${team.companyId}/ownership`);
  await recipientPage.getByRole("button", { name: "Accept ownership" }).focus();
  await recipientPage.keyboard.press("Enter");
  await expect(notice(recipientPage)).toHaveText(`You are now the Owner of ${team.companyName}.`);
  await other.close();
});
