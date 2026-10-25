import {
  PASSWORD,
  alertIn,
  createVerifiedAccount,
  expect,
  expectNoSeriousA11yViolations,
  inviteByApi,
  linkIn,
  signUp,
  test,
  uniqueEmail,
  waitForMail,
} from "./support";

const SUBJECT = "You have been invited to a workspace";

/** An owner with a company who has invited the given address; returns the company name and the emailed link. */
async function invitation(page: import("@playwright/test").Page, inviteeEmail: string) {
  const ownerEmail = uniqueEmail();
  await createVerifiedAccount(page, ownerEmail);
  const company = await inviteByApi(ownerEmail, inviteeEmail);
  const link = linkIn(await waitForMail(inviteeEmail, SUBJECT), "invitations/accept");
  return { company, link };
}

async function signInOnPage(page: import("@playwright/test").Page, email: string) {
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
}

test("an existing account follows the link, signs in, comes back and joins", async ({ page, context }) => {
  const invitee = uniqueEmail();
  await createVerifiedAccount(page, invitee);
  const { company, link } = await invitation(page, invitee);
  await context.clearCookies();

  const response = await page.goto(link);
  expect(response?.headers()["referrer-policy"]).toBe("no-referrer");
  await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
  // The token is gone from the address bar as soon as the page has read it, and was never in the path or query.
  expect(page.url()).toBe(link.split("#")[0]);
  expect(page.url()).not.toContain("token");
  await expect(page.getByText(company)).toHaveCount(0);

  await page.getByRole("link", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/login\?next=invitation$/);
  await signInOnPage(page, invitee);

  await expect(page).toHaveURL(/\/invitations\/accept$/);
  await expect(page.getByText("Join this company?")).toBeVisible();
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("status")).toHaveText(`You joined ${company} as Viewer.`);
});

test("a new person signs up with the invited address, verifies it, and joins from the same tab", async ({
  page,
  context,
}) => {
  const invitee = uniqueEmail();
  const { company, link } = await invitation(page, invitee);
  await context.clearCookies();

  await page.goto(link);
  await page.getByRole("link", { name: "Create an account" }).click();
  await expect(page).toHaveURL(/\/signup\?next=invitation$/);
  await expect(page.getByRole("status")).toHaveText("Use the address the invitation was sent to.");
  await signUp(page, invitee);

  // Following the invitation link did not verify the address; the usual verification email is still needed.
  await page.goto("/login");
  await signInOnPage(page, invitee);
  await expect(alertIn(page)).toContainText("email or password is incorrect");

  await page.goto(linkIn(await waitForMail(invitee, "Verify your email"), "verify-email"));
  await expect(page.getByText("Your email address is verified.")).toBeVisible();
  await page.goto("/login?next=invitation");
  await signInOnPage(page, invitee);

  // The token waited in this tab's session storage; no new fragment was needed.
  await expect(page).toHaveURL(/\/invitations\/accept$/);
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("status")).toHaveText(`You joined ${company} as Viewer.`);
});

test("another account gets the same answer as an unknown link, and a spent link cannot be replayed", async ({
  page,
  context,
}) => {
  const invitee = uniqueEmail();
  const stranger = uniqueEmail();
  await createVerifiedAccount(page, stranger);
  const { link } = await invitation(page, invitee);
  const invalid = "This invitation link is invalid or has expired. Ask the person who invited you to send a new one.";

  await context.clearCookies();
  await page.goto("/login?next=invitation");
  await signInOnPage(page, stranger);
  await expect(page).toHaveURL(/\/invitations\/accept$/);
  await page.goto(link);
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(alertIn(page)).toHaveText(invalid);

  // An address that does not belong to the invited person changed nothing: the right person can still join.
  await context.clearCookies();
  await createVerifiedAccount(page, invitee);
  await page.goto("/login?next=invitation");
  await signInOnPage(page, invitee);
  await expect(page).toHaveURL(/\/invitations\/accept$/);
  await page.goto(link);
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page.getByRole("status")).toContainText("You joined");

  await page.goto(link);
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(alertIn(page)).toHaveText(invalid);

  await page.goto("/invitations/accept");
  await expect(alertIn(page)).toHaveText(invalid);
  await page.goto("/invitations/accept?token=abc");
  await expect(alertIn(page)).toHaveText(invalid);
});

test("the accept page has no serious accessibility violations", async ({ page, context }) => {
  const invitee = uniqueEmail();
  await createVerifiedAccount(page, invitee);
  const { link } = await invitation(page, invitee);
  await context.clearCookies();

  await page.goto(link);
  await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);

  await page.goto("/login?next=invitation");
  await signInOnPage(page, invitee);
  await expect(page).toHaveURL(/\/invitations\/accept$/);
  await page.goto(link);
  await expect(page.getByRole("button", { name: "Accept invitation" })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
});
