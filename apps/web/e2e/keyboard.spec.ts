import type { Page } from "@playwright/test";

import { PASSWORD, expect, makeTeam, signIn, test } from "./support";

// Building the team takes a while (sign-up and invitation mail for each account).
test.describe.configure({ timeout: 240_000 });

function row(page: Page, name: string) {
  return page.getByRole("listitem").filter({ hasText: name });
}

/**
 * Whether focus has reached the page behind an open modal dialog. A native modal dialog makes the page inert, so Tab
 * moves between its own controls and the browser's own interface, and never to the header or the content behind it.
 */
async function focusReachedThePageBehind(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const active = document.activeElement;
    const behind = document.querySelector("header, main");
    const dialog = active?.closest("dialog");
    return !!active && !dialog && !!behind && (document.querySelector("header")?.contains(active) || document.querySelector("main")?.contains(active) || false);
  });
}

test("every members and invitations action can be done with the keyboard alone", async ({ page, context }) => {
  const team = await makeTeam(page, ["AGENT", "VIEWER"]);
  await context.clearCookies();
  await signIn(page, team.ownerEmail, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(page.getByRole("heading", { name: "Members (3)" })).toBeVisible();

  // Focus is visible: a control reached by keyboard has an outline.
  await page.getByRole("button", { name: "Invite someone" }).focus();
  await page.keyboard.press("Shift+Tab");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Invite someone" })).toBeFocused();
  const outline = await page.getByRole("button", { name: "Invite someone" }).evaluate((el) => getComputedStyle(el).outlineStyle);
  expect(outline).not.toBe("none");

  // A confirmation: Enter opens it, Cancel has focus first, Tab and Shift+Tab never reach the page behind, Escape returns focus.
  const suspend = row(page, "Cy Agent").getByRole("button", { name: "Suspend Cy Agent" });
  await suspend.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Suspend Cy Agent?" });
  await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  for (let i = 0; i < 5; i++) {
    await page.keyboard.press("Tab");
    expect(await focusReachedThePageBehind(page), `Tab ${i + 1}`).toBe(false);
  }
  await page.keyboard.press("Shift+Tab");
  expect(await focusReachedThePageBehind(page)).toBe(false);
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(suspend).toBeFocused();

  // Confirm by keyboard: Enter opens, Tab to the confirm button, Enter.
  await page.keyboard.press("Enter");
  await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(dialog.getByRole("button", { name: "Suspend" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status").filter({ hasText: /\S/ })).toHaveText("Cy Agent was suspended.");
  await expect(row(page, "Cy Agent")).toContainText("Suspended");

  // Reactivate with Space on the focused button.
  const reactivate = row(page, "Cy Agent").getByRole("button", { name: "Reactivate Cy Agent" });
  await reactivate.focus();
  await page.keyboard.press("Space");
  await expect(row(page, "Cy Agent")).toContainText("Active");

  // The role select changes with the arrow keys.
  const roles = row(page, "Di Viewer").getByRole("combobox", { name: "Role of Di Viewer" });
  await roles.focus();
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("status").filter({ hasText: /\S/ })).toContainText("Di Viewer is now");

  // The invite dialog: opened by keyboard, focus starts in the address field, Tab never reaches the page behind, Escape closes and returns.
  const invite = page.getByRole("button", { name: "Invite someone" });
  await invite.focus();
  await page.keyboard.press("Enter");
  const inviteDialog = page.getByRole("dialog", { name: "Invite someone" });
  await expect(inviteDialog.getByLabel("Email address")).toBeFocused();
  for (let i = 0; i < 6; i++) {
    await page.keyboard.press("Tab");
    expect(await focusReachedThePageBehind(page), `Tab ${i + 1}`).toBe(false);
  }
  await page.keyboard.press("Escape");
  await expect(inviteDialog).toBeHidden();
  await expect(invite).toBeFocused();

  // The tabs are links reachable by keyboard and say which one is current.
  await page.getByRole("link", { name: "Pending invitations" }).focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/invitations$/);
  await expect(page.getByRole("link", { name: "Pending invitations" })).toHaveAttribute("aria-current", "page");
});

test("pressing Escape twice while a change is running does not break the confirmation", async ({ page, context }) => {
  const team = await makeTeam(page, ["AGENT"]);
  await context.clearCookies();
  await signIn(page, team.ownerEmail, PASSWORD);
  await page.getByRole("link", { name: `Open ${team.companyName}` }).click();
  await expect(row(page, "Cy Agent")).toBeVisible();

  // Make the removal slow, so the dialog is still busy when Escape is pressed.
  await page.route("**/api/companies/*/members/*", async (route) => {
    if (route.request().method() === "DELETE") {
      await new Promise((resolve) => setTimeout(resolve, 2500));
    }
    await route.continue();
  });
  await row(page, "Cy Agent").getByRole("button", { name: "Remove Cy Agent" }).click();
  const dialog = page.getByRole("dialog", { name: "Remove Cy Agent?" });
  await dialog.getByRole("button", { name: "Remove" }).click();
  await expect(dialog.getByRole("button", { name: "Removing…" })).toBeVisible();

  await page.keyboard.press("Escape");
  await page.keyboard.press("Escape");
  // The dialog is still there while the request runs, whatever the browser did with the second Escape.
  await expect(dialog).toBeVisible();

  // When it finishes the dialog closes, the row is gone, and the page still works.
  await expect(page.getByRole("heading", { name: "Members (1)" })).toBeVisible();
  await expect(dialog).toBeHidden();
  await page.getByRole("button", { name: "Invite someone" }).click();
  await expect(page.getByRole("dialog", { name: "Invite someone" })).toBeVisible();
});
