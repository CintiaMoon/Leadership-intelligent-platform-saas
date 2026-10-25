import type { Page } from "@playwright/test";

import {
  PASSWORD,
  apiLogin,
  expect,
  expectNoSeriousA11yViolations,
  makeTeam,
  signIn,
  test,
} from "./support";

const SHOTS = "test-results/f1-3";

// Each test first builds a team of real accounts (sign-up, verification mail, invitation mail), which takes a while.
test.describe.configure({ timeout: 180_000 });

function row(page: Page, name: string) {
  return page.getByRole("listitem").filter({ hasText: name });
}

async function shots(page: Page, name: string) {
  for (const [label, width, height] of [["360", 360, 780], ["768", 768, 900], ["1280", 1280, 800]] as const) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${SHOTS}/${name}-${label}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 1280, height: 720 });
}

test("an Owner manages members: change role, suspend, reactivate, remove", async ({ page, context }) => {
  const team = await makeTeam(page, ["ADMIN", "AGENT", "VIEWER"]);
  await context.clearCookies();
  await signIn(page, team.ownerEmail, PASSWORD);
  await expect(page).toHaveURL(/\/companies$/);

  // Open goes straight to Members for an Owner.
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(page).toHaveURL(/\/companies\/[0-9a-f-]{36}\/members$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText(team.companyName);
  await expect(page.getByRole("link", { name: "Members" })).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("heading", { name: "Members (4)" })).toBeVisible();
  await expect(page.getByText("Transfer ownership first.")).toBeVisible();

  // Your own row has no controls; the others have them.
  const own = row(page, "Olivia Owner");
  await expect(own).toContainText("(you)");
  await expect(own.getByRole("button")).toHaveCount(0);
  await expect(own.getByRole("combobox")).toHaveCount(0);
  await expect(row(page, "Cy Agent").getByRole("button", { name: "Suspend Cy Agent" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
  await shots(page, "members-owner");

  // Change a role (no password needed: the sign-in was just now).
  await row(page, "Cy Agent").getByRole("combobox", { name: "Role of Cy Agent" }).selectOption("VIEWER");
  await expect(page.getByRole("status")).toHaveText("Cy Agent is now a viewer.");
  await expect(row(page, "Cy Agent").getByRole("combobox")).toHaveValue("VIEWER");

  // Suspend: asks first, names the person.
  await row(page, "Di Viewer").getByRole("button", { name: "Suspend Di Viewer" }).click();
  const dialog = page.getByRole("dialog", { name: "Suspend Di Viewer?" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  await expectNoSeriousA11yViolations(page);
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await row(page, "Di Viewer").getByRole("button", { name: "Suspend Di Viewer" }).click();
  await dialog.getByRole("button", { name: "Suspend" }).click();
  await expect(page.getByRole("status")).toHaveText("Di Viewer was suspended.");
  await expect(row(page, "Di Viewer")).toContainText("Suspended");
  await expect(row(page, "Di Viewer").getByRole("combobox")).toHaveCount(0);
  await shots(page, "members-suspended");

  // Reactivate keeps the role.
  await row(page, "Di Viewer").getByRole("button", { name: "Reactivate Di Viewer" }).click();
  await expect(page.getByRole("status")).toHaveText("Di Viewer was reactivated.");
  await expect(row(page, "Di Viewer")).toContainText("Active");
  await expect(row(page, "Di Viewer").getByRole("combobox")).toHaveValue("VIEWER");

  // Remove: the row disappears and the count drops.
  await row(page, "Bo Admin").getByRole("button", { name: "Remove Bo Admin" }).click();
  const removeDialog = page.getByRole("dialog", { name: "Remove Bo Admin?" });
  await expect(removeDialog).toContainText("They will need a new invitation to rejoin.");
  await removeDialog.getByRole("button", { name: "Remove" }).click();
  await expect(page.getByRole("heading", { name: "Members (3)" })).toBeVisible();
  await expect(row(page, "Bo Admin")).toHaveCount(0);
  await expect(page.getByRole("status")).toHaveText("Bo Admin was removed.");
});

test("an Admin has fewer controls, an Agent cannot open Members, and an Admin can leave", async ({ page, context }) => {
  const team = await makeTeam(page, ["ADMIN", "AGENT"]);
  const admin = team.members.ADMIN!;
  const agent = team.members.AGENT!;
  await context.clearCookies();

  await signIn(page, admin.email, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(page).toHaveURL(/\/members$/);
  await expect(page.getByRole("heading", { name: "Members (3)" })).toBeVisible();
  for (const name of ["Olivia Owner", "Bo Admin"]) {
    await expect(row(page, name).getByRole("button")).toHaveCount(0);
    await expect(row(page, name).getByRole("combobox")).toHaveCount(0);
  }
  const roles = row(page, "Cy Agent").getByRole("combobox");
  await expect(roles.locator("option")).toHaveText(["Agent", "Viewer"]);
  await expectNoSeriousA11yViolations(page);
  await shots(page, "members-admin");

  // An Admin can leave; the company is gone from their list afterwards.
  await page.getByRole("button", { name: "Leave company" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Leave company" }).click();
  await expect(page).toHaveURL(/\/companies$/);
  await expect(page.getByRole("link", { name: `Open ${team.companyName}` })).toHaveCount(0);
  const membersUrl = `/companies/${team.companyId}/members`;
  await page.goto(membersUrl);
  await expect(page.getByRole("heading", { name: "This page is not available." })).toBeVisible();

  // An Agent sees the placeholder, no tabs, and the Members address is just "not available".
  await context.clearCookies();
  await signIn(page, agent.email, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(page).toHaveURL(new RegExp(`/companies/${team.companyId}$`));
  await expect(page.getByText("Company home is coming")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Company" })).toHaveCount(0);
  await page.goto(membersUrl);
  await expect(page.getByRole("heading", { name: "This page is not available." })).toBeVisible();
  await expect(page.getByText("agent@")).toHaveCount(0);
});

test("a row that changed since the page loaded is explained and the list refreshes", async ({ page, context }) => {
  const team = await makeTeam(page, ["AGENT"]);
  await context.clearCookies();
  await signIn(page, team.ownerEmail, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(row(page, "Cy Agent")).toBeVisible();

  // Somebody else (the Owner in another window) removes Cy while this page still shows the member.
  const owner = await apiLogin(team.ownerEmail);
  const list = (await (await owner.send("GET", `/api/v1/companies/${team.companyId}/members`)).json()) as { id: string; displayName: string }[];
  const cy = list.find((member) => member.displayName === "Cy Agent")!;
  expect((await owner.send("DELETE", `/api/v1/companies/${team.companyId}/members/${cy.id}`)).status()).toBe(204);
  await owner.dispose();

  await row(page, "Cy Agent").getByRole("button", { name: "Suspend Cy Agent" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Suspend" }).click();

  await expect(page.getByRole("status")).toContainText("changed in the meantime");
  await expect(row(page, "Cy Agent")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Members (1)" })).toBeVisible();
});
