import type { Page } from "@playwright/test";

import {
  PASSWORD,
  apiLogin,
  expect,
  expectNoSeriousA11yViolations,
  fillActiveMembers,
  makeTeam,
  placeSubscription,
  signIn,
  test,
} from "./support";

const SHOTS = "test-results/plan-phase5";

// Building the team takes a while (sign-up and invitation mail for each account).
test.describe.configure({ timeout: 300_000 });

/** Signs in and waits until the companies list shows, so the session cookie is there before the next page is opened. */
async function signInAndWait(page: Page, email: string) {
  await signIn(page, email, PASSWORD);
  await expect(page).toHaveURL(/\/companies$/);
}

function row(page: Page, name: string) {
  return page.getByRole("listitem").filter({ hasText: name });
}

/** The plan banner: plain text with a fixed id, not a live region. */
function banner(page: Page, text: RegExp) {
  return page.locator("#plan-status").filter({ hasText: text });
}

async function shots(page: Page, name: string) {
  for (const [label, width, height] of [["360", 360, 780], ["768", 768, 900], ["1280", 1280, 800]] as const) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${SHOTS}/${name}-${label}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 1280, height: 720 });
}

/** Counts the requests that change something, so a refused control can be shown to send nothing. */
function changes(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request) => {
    if (request.method() !== "GET" && request.url().includes("/api/companies/")) {
      seen.push(`${request.method()} ${new URL(request.url()).pathname}`);
    }
  });
  return seen;
}

test("a company moves through the trial, read-only, locked and active, and the screens follow the server", async ({ page, context }) => {
  const team = await makeTeam(page, ["ADMIN", "AGENT", "VIEWER"]);
  const owner = await apiLogin(team.ownerEmail);
  const members = (await (await owner.send("GET", `/api/v1/companies/${team.companyId}/members`)).json()) as {
    id: string;
    displayName: string;
  }[];
  // One member is suspended while the plan still allows it, so that Reactivate has something to act on.
  const viewer = members.find((m) => m.displayName === "Di Viewer")!;
  expect((await owner.send("POST", `/api/v1/companies/${team.companyId}/members/${viewer.id}/suspend`)).ok()).toBe(true);

  await context.clearCookies();
  await signInAndWait(page, team.ownerEmail);
  const sent = changes(page);
  const members_url = `/companies/${team.companyId}/members`;

  await test.step("the trial is running: a banner with the days left, everything works", async () => {
    placeSubscription(team.companyId, { kind: "trial", daysLeft: 9 });
    await page.goto(members_url);
    await expect(banner(page, /days left in your trial/)).toHaveText("9 days left in your trial.");
    await expect(page.getByRole("button", { name: "Invite someone" })).not.toHaveAttribute("aria-disabled", "true");
    await expect(page.getByRole("button", { name: "Reactivate Di Viewer" })).not.toHaveAttribute("aria-disabled", "true");
    await expectNoSeriousA11yViolations(page);
    await shots(page, "members-trial");

    await page.getByRole("link", { name: "Plan" }).click();
    await expect(page).toHaveURL(/\/plan$/);
    await expect(page.getByRole("link", { name: "Plan" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByText("Trial (version 1)")).toBeVisible();
    await expect(page.getByText("Plan changes are made by our team")).toBeVisible();
    await expect(page.getByRole("main").getByRole("button")).toHaveCount(0);
    await expect(page.getByText("3 of 15 used")).toBeVisible();
    await expectNoSeriousA11yViolations(page);
    await shots(page, "plan-trial");
  });

  await test.step("read-only: reading and ending things work, inviting, role changes and reactivating say why they do not", async () => {
    placeSubscription(team.companyId, { kind: "read-only" });
    await page.goto(members_url);
    await expect(banner(page, /trial has ended/)).toBeVisible();
    const invite = page.getByRole("button", { name: "Invite someone" });
    await expect(invite).toHaveAttribute("aria-disabled", "true");
    await expect(invite).toHaveAccessibleDescription(/trial has ended/);
    await expect(row(page, "Cy Agent").getByRole("combobox", { name: "Role of Cy Agent" })).toBeDisabled();
    const reactivate = row(page, "Di Viewer").getByRole("button", { name: "Reactivate Di Viewer" });
    await expect(reactivate).toHaveAttribute("aria-disabled", "true");
    await expect(reactivate).toHaveAccessibleDescription(/read-only/);
    await expect(row(page, "Cy Agent").getByRole("button", { name: "Suspend Cy Agent" })).not.toHaveAttribute("aria-disabled", "true");
    await expectNoSeriousA11yViolations(page);
    await shots(page, "members-read-only");

    // A refused control opens nothing and sends nothing (Playwright treats aria-disabled as not clickable, so force the click).
    sent.length = 0;
    await invite.click({ force: true });
    await reactivate.click({ force: true });
    await expect(page.getByRole("dialog")).toHaveCount(0);
    expect(sent).toEqual([]);

    // The keyboard reaches the refused control and its reason, and Enter does nothing.
    await reactivate.focus();
    await expect(reactivate).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    expect(sent).toEqual([]);

    await page.goto(`/companies/${team.companyId}/invitations`);
    await expect(banner(page, /trial has ended/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Invite someone" })).toHaveAttribute("aria-disabled", "true");

    await page.goto(`/companies/${team.companyId}/plan`);
    await expect(page.getByText("Trial ended (read-only)")).toBeVisible();
    await expectNoSeriousA11yViolations(page);
    await shots(page, "plan-read-only");

    await page.goto(`/companies/${team.companyId}/ownership`);
    await expect(banner(page, /trial has ended/)).toBeVisible();
  });

  await test.step("locked: the Owner still opens the company and sees why nothing can be changed", async () => {
    placeSubscription(team.companyId, { kind: "locked" });
    await page.goto(members_url);
    await expect(banner(page, /company is locked/)).toBeVisible();
    await expect(row(page, "Cy Agent").getByRole("button", { name: "Suspend Cy Agent" })).toHaveAttribute("aria-disabled", "true");
    await expect(row(page, "Cy Agent").getByRole("button", { name: "Remove Cy Agent" })).toHaveAttribute("aria-disabled", "true");
    await expectNoSeriousA11yViolations(page);
    await shots(page, "members-locked");
    await page.goto(`/companies/${team.companyId}/plan`);
    await expect(page.getByText("Plan ended (locked)")).toBeVisible();
    await shots(page, "plan-locked");
  });

  await test.step("locked: an Admin and an Agent get the calm page, and a suspended member still gets 'not available'", async () => {
    for (const role of ["ADMIN", "AGENT"] as const) {
      const member = team.members[role]!;
      await context.clearCookies();
      await signInAndWait(page, member.email);
      for (const path of [`/companies/${team.companyId}`, `/companies/${team.companyId}/members`, `/companies/${team.companyId}/plan`]) {
        await page.goto(path);
        await expect(page.getByRole("heading", { name: "This company is locked." }), `${role} ${path}`).toBeVisible();
        await expect(page.getByText("only its Owner can open it")).toBeVisible();
      }
      await expectNoSeriousA11yViolations(page);
      if (role === "ADMIN") {
        await shots(page, "locked-for-admin");
      }
    }
    // The suspended Viewer is not a member any more as far as the company goes: nothing is said about its plan.
    await context.clearCookies();
    await signInAndWait(page, team.members.VIEWER!.email);
    await page.goto(`/companies/${team.companyId}/members`);
    await expect(page.getByText("This page is not available.")).toBeVisible();
    await expect(page.getByText("This company is locked.")).toHaveCount(0);
    await context.clearCookies();
    await signInAndWait(page, team.ownerEmail);
  });

  await test.step("an operator assigns a plan: the same screens work again with no banner", async () => {
    placeSubscription(team.companyId, { kind: "active", plan: "TEAM" });
    await page.goto(members_url);
    await expect(page.locator("#plan-status")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Invite someone" })).not.toHaveAttribute("aria-disabled", "true");
    await expect(row(page, "Cy Agent").getByRole("combobox", { name: "Role of Cy Agent" })).toBeEnabled();
    await expect(row(page, "Di Viewer").getByRole("button", { name: "Reactivate Di Viewer" })).not.toHaveAttribute("aria-disabled", "true");
    await page.goto(`/companies/${team.companyId}/plan`);
    await expect(page.getByText("Team (version 1)")).toBeVisible();
    await expect(page.getByText("Active", { exact: true })).toBeVisible();
    await expectNoSeriousA11yViolations(page);
    await shots(page, "plan-active");
  });

  await test.step("at the member limit only reactivating is off; above it the plan page says nobody was removed", async () => {
    placeSubscription(team.companyId, { kind: "active", plan: "STARTER" });
    fillActiveMembers(team.companyId, 15);
    await page.goto(members_url);
    const reactivate = row(page, "Di Viewer").getByRole("button", { name: "Reactivate Di Viewer" });
    await expect(reactivate).toHaveAttribute("aria-disabled", "true");
    await expect(reactivate).toHaveAccessibleDescription(/member limit is reached/);
    await expect(page.getByRole("button", { name: "Invite someone" })).not.toHaveAttribute("aria-disabled", "true");
    await expectNoSeriousA11yViolations(page);
    await shots(page, "members-limit-reached");

    fillActiveMembers(team.companyId, 18);
    await page.goto(`/companies/${team.companyId}/plan`);
    await expect(page.getByText("18 of 15 used")).toBeVisible();
    await expect(page.getByText(/Nobody was removed or suspended/)).toBeVisible();
    await expectNoSeriousA11yViolations(page);
    await shots(page, "plan-over-limit");
  });

  await owner.dispose();
});

test("an Agent or a Viewer sees no Plan tab and cannot open the plan page", async ({ page, context }) => {
  const team = await makeTeam(page, ["AGENT"]);
  await context.clearCookies();
  await signInAndWait(page, team.members.AGENT!.email);
  await page.goto(`/companies/${team.companyId}`);
  await expect(page.getByRole("link", { name: "Plan" })).toHaveCount(0);
  await page.goto(`/companies/${team.companyId}/plan`);
  await expect(page.getByText("This page is not available.")).toBeVisible();
});
