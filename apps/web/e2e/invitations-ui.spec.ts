import type { Page } from "@playwright/test";

import {
  PASSWORD,
  apiLogin,
  createVerifiedAccount,
  expect,
  expectNoSeriousA11yViolations,
  linkIn,
  makeTeam,
  signIn,
  test,
  uniqueEmail,
  waitForMail,
} from "./support";

const SHOTS = "test-results/f1-4";
const SUBJECT = "You have been invited to a workspace";

// Each test first builds real accounts (sign-up, verification mail, invitation mail), which takes a while.
test.describe.configure({ timeout: 240_000 });

function row(page: Page, text: string) {
  return page.getByRole("listitem").filter({ hasText: text });
}

async function invite(page: Page, email: string, role?: string) {
  await page.getByRole("button", { name: "Invite someone" }).click();
  const dialog = page.getByRole("dialog", { name: "Invite someone" });
  await dialog.getByLabel("Email address").fill(email);
  if (role) {
    await dialog.getByLabel("Role").selectOption(role);
  }
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  return dialog;
}

/** The newest invitation link for an address that differs from `previous` (a resend mails a new link). */
async function newerLink(email: string, previous?: string): Promise<string> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const link = linkIn(await waitForMail(email, SUBJECT), "invitations/accept");
    if (link !== previous) {
      return link;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("no new invitation link arrived");
}

async function shots(page: Page, name: string) {
  for (const [label, width, height] of [["360", 360, 780], ["768", 768, 900], ["1280", 1280, 800]] as const) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${SHOTS}/${name}-${label}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 1280, height: 720 });
}

test("an Owner invites from the screen, resends, revokes, and the invited person joins", async ({ page, context }) => {
  const team = await makeTeam(page, []);
  const invitee = uniqueEmail();
  await createVerifiedAccount(page, invitee, PASSWORD, "Ina Invitee");
  await context.clearCookies();
  await signIn(page, team.ownerEmail, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(page).toHaveURL(/\/members$/);

  // An invalid address is a field error and the dialog stays open.
  let dialog = await invite(page, "not-an-address");
  await expect(dialog.getByLabel("Email address")).toHaveAttribute("aria-invalid", "true");
  await expect(dialog.getByText("Enter a valid email address.")).toBeVisible();
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();

  // A good one: the dialog closes and says where it went, with a link to the pending list.
  dialog = await invite(page, invitee, "AGENT");
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("status").filter({ hasText: "Invitation sent to" })).toContainText(`Invitation sent to ${invitee}.`);
  await page.getByRole("link", { name: "View pending invitations" }).click();
  await expect(page).toHaveURL(/\/invitations$/);
  await expect(page.getByRole("link", { name: "Pending invitations" })).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("heading", { name: "Pending invitations (1)" })).toBeVisible();
  await expect(row(page, invitee)).toContainText("Agent");
  await expect(row(page, invitee)).toContainText("Expires");
  await expectNoSeriousA11yViolations(page);
  await shots(page, "pending-owner");

  // The same address again is one calm conflict message, whatever the reason.
  dialog = await invite(page, invitee, "AGENT");
  await expect(dialog.getByRole("alert")).toContainText("already a member or already has a pending invitation");
  await expectNoSeriousA11yViolations(page);
  await page.setViewportSize({ width: 360, height: 780 });
  await page.screenshot({ path: `${SHOTS}/invite-dialog-360.png` });
  await page.setViewportSize({ width: 1280, height: 720 });
  await dialog.getByRole("button", { name: "Cancel" }).click();

  // The email went out; a resend mails a new link and the old one stops working.
  const first = await newerLink(invitee);
  await row(page, invitee).getByRole("button", { name: `Resend the invitation to ${invitee}` }).click();
  await expect(page.getByRole("status").filter({ hasText: "was sent again" })).toHaveText(`The invitation to ${invitee} was sent again.`);
  const second = await newerLink(invitee, first);

  // Revoke another invitation: asks first, names the address, the row disappears.
  const other = uniqueEmail();
  dialog = await invite(page, other, "VIEWER");
  await expect(row(page, other)).toBeVisible();
  await row(page, other).getByRole("button", { name: `Revoke the invitation to ${other}` }).click();
  const revoke = page.getByRole("dialog", { name: `Revoke the invitation to ${other}?` });
  await expect(revoke).toBeVisible();
  await revoke.getByRole("button", { name: "Revoke" }).click();
  await expect(row(page, other)).toHaveCount(0);
  await expect(page.getByRole("status").filter({ hasText: "was revoked" })).toHaveText(`The invitation to ${other} was revoked.`);

  // The invited person accepts with the newest link and appears in Members; nothing is pending any more.
  await context.clearCookies();
  await page.goto(second);
  await page.getByRole("link", { name: "Sign in" }).click();
  await page.getByLabel("Work email").fill(invitee);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("status")).toContainText("as Agent.");

  await context.clearCookies();
  await signIn(page, team.ownerEmail, PASSWORD);
  await expect(page).toHaveURL(/\/companies$/);
  await page.goto(`/companies/${team.companyId}/invitations`);
  await expect(page.getByText("No pending invitations.")).toBeVisible();
  await page.getByRole("link", { name: "Members", exact: true }).click();
  await expect(row(page, "Ina Invitee")).toContainText("Agent");
});

test("an Admin sees Admin invitations without actions and can only invite Agents and Viewers", async ({ page, context }) => {
  const team = await makeTeam(page, ["ADMIN"]);
  const admin = team.members.ADMIN!;
  const owner = await apiLogin(team.ownerEmail);
  const adminInvitee = uniqueEmail();
  expect(
    (await owner.send("POST", `/api/v1/companies/${team.companyId}/invitations`, { email: adminInvitee, role: "ADMIN" })).status(),
  ).toBe(201);
  await owner.dispose();
  await context.clearCookies();

  await signIn(page, admin.email, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await page.getByRole("link", { name: "Pending invitations" }).click();
  await expect(row(page, adminInvitee)).toContainText("Admin");
  await expect(row(page, adminInvitee).getByRole("button")).toHaveCount(0);

  await page.getByRole("button", { name: "Invite someone" }).click();
  const dialog = page.getByRole("dialog", { name: "Invite someone" });
  await expect(dialog.getByLabel("Role").locator("option")).toHaveText(["Viewer", "Agent"]);
  await dialog.getByLabel("Email address").fill(uniqueEmail());
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("heading", { name: "Pending invitations (2)" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
  await shots(page, "pending-admin");
});

test("an Agent has no Invite button and the invitations address is not available", async ({ page, context }) => {
  const team = await makeTeam(page, ["AGENT"]);
  await context.clearCookies();
  await signIn(page, team.members.AGENT!.email, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(page.getByText("Company home is coming")).toBeVisible();
  await expect(page.getByRole("button", { name: "Invite someone" })).toHaveCount(0);

  await page.goto(`/companies/${team.companyId}/invitations`);
  await expect(page.getByRole("heading", { name: "This page is not available." })).toBeVisible();
  await expect(page.getByRole("button", { name: "Invite someone" })).toHaveCount(0);
});
