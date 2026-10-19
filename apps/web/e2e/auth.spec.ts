import {
  NEW_PASSWORD,
  PASSWORD,
  alertIn,
  createVerifiedAccount,
  expect,
  expectNoSeriousA11yViolations,
  linkIn,
  signIn,
  signUp,
  test,
  uniqueEmail,
  waitForMail,
} from "./support";

test("signup, email verification, login, profile and logout", async ({ page, context }) => {
  const email = uniqueEmail();
  await signUp(page, email);
  await expect(page.getByRole("heading", { name: "Check your email." })).toBeVisible();

  // An unverified account cannot sign in, and the message does not say why.
  await signIn(page, email, PASSWORD);
  await expect(alertIn(page)).toContainText("email or password is incorrect");

  const link = linkIn(await waitForMail(email, "Verify your email"), "verify-email");
  await page.goto(link);
  await expect(page.getByText("Your email address is verified.")).toBeVisible();
  expect(page.url()).not.toContain("token");
  await page.getByRole("link", { name: "Continue to sign in" }).click();
  await expect(page.getByRole("status")).toContainText("Your email is verified");

  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/account$/);
  await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
  await expect(page.getByText("Alex Morgan").first()).toBeVisible();
  await expect(page.getByText(email).first()).toBeVisible();

  // The session cookie is hardened and invisible to scripts.
  const sessionName = process.env.E2E_PROD_COOKIES === "true" ? "__Host-session" : "session";
  const session = (await context.cookies()).find((cookie) => cookie.name === sessionName);
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("Lax");
  expect(session?.path).toBe("/");
  if (process.env.E2E_PROD_COOKIES === "true") {
    expect(session?.secure).toBe(true);
  }
  expect(await page.evaluate(() => document.cookie)).not.toContain("session");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto("/account");
  await expect(page).toHaveURL(/\/login$/);
  expect((await context.cookies()).find((cookie) => cookie.name === sessionName)).toBeUndefined();
});

test("a verification link works once", async ({ page }) => {
  const email = uniqueEmail();
  await signUp(page, email);
  const link = linkIn(await waitForMail(email, "Verify your email"), "verify-email");

  await page.goto(link);
  await expect(page.getByText("Your email address is verified.")).toBeVisible();

  await page.goto(link);
  await expect(alertIn(page)).toContainText("invalid or has expired");
  await expect(page.getByRole("button", { name: "Send a new link" })).toBeVisible();
});

test("the resend form gives the same answer for any address", async ({ page }) => {
  await page.goto("/check-email");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByRole("button", { name: "Send a new link" }).click();
  await expect(page.getByRole("status")).toContainText(
    "If an unverified account exists for this address, we have sent a new verification email.",
  );
});

test("a link without a token explains itself", async ({ page }) => {
  await page.goto("/reset-password");
  await expect(alertIn(page)).toContainText("incomplete");
  await page.goto("/verify-email");
  await expect(alertIn(page)).toContainText("incomplete");
});

test("password reset: neutral request, policy check, one-time link, sessions revoked", async ({
  page,
  browser,
}) => {
  const email = uniqueEmail();
  await createVerifiedAccount(page, email);

  // A second browser is signed in and must lose its session when the password changes.
  const other = await browser.newContext();
  const otherPage = await other.newPage();
  await signIn(otherPage, email, PASSWORD);
  await expect(otherPage).toHaveURL(/\/account$/);

  // Unknown and known addresses look the same.
  const neutral = "If an account exists for this address, we have sent password reset instructions.";
  await page.goto("/forgot-password");
  await page.getByLabel("Work email").fill(uniqueEmail());
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByRole("status")).toContainText(neutral);

  await page.goto("/forgot-password");
  await page.getByLabel("Work email").fill(email);
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByRole("status")).toContainText(neutral);

  const link = linkIn(await waitForMail(email, "Reset your password"), "reset-password");
  const requests: string[] = [];
  page.on("request", (request) => requests.push(request.url() + (request.postData() ? " [body]" : "")));
  await page.goto(link);
  const token = link.split("#token=")[1];

  // The token leaves the address bar and is sent only in a request body.
  await expect(page.getByLabel("New password")).toBeVisible();
  expect(page.url()).not.toContain(token);
  expect(requests.filter((url) => url.includes(token))).toEqual([]);

  // A breached password is refused and the same link still works.
  await page.getByLabel("New password").fill("password123");
  await page.getByRole("button", { name: "Set new password" }).click();
  await expect(page.getByText("known data breaches")).toBeVisible();

  await page.getByLabel("New password").fill(NEW_PASSWORD);
  await page.getByRole("button", { name: "Set new password" }).click();
  await expect(page).toHaveURL(/\/login\?notice=reset$/);
  await expect(page.getByRole("status")).toContainText("Your password was changed");
  await waitForMail(email, "Your password was changed");

  // The other browser was signed out everywhere.
  await otherPage.goto("/account");
  await expect(otherPage).toHaveURL(/\/login\?notice=expired$/);
  await expect(otherPage.getByRole("status")).toContainText("Your session has ended");
  await other.close();

  // Old password fails, new password works, and the link is spent.
  await signIn(page, email, PASSWORD);
  await expect(alertIn(page)).toContainText("email or password is incorrect");
  await signIn(page, email, NEW_PASSWORD);
  await expect(page).toHaveURL(/\/account$/);

  await page.goto(link);
  await page.getByLabel("New password").fill("yet another passphrase 99!");
  await page.getByRole("button", { name: "Set new password" }).click();
  await expect(alertIn(page)).toContainText("invalid or has expired");
});

test("the BFF refuses a state-changing request from another origin and unlisted operations", async ({
  page,
}) => {
  const crossOrigin = await page.request.post("/api/auth/login", {
    headers: { origin: "https://evil.example", "content-type": "application/json" },
    data: { email: "a@example.com", password: "x" },
  });
  expect(crossOrigin.status()).toBe(403);

  const unlisted = await page.request.post("/api/auth/step-up", {
    headers: { origin: new URL(page.url() === "about:blank" ? "http://localhost:3100" : page.url()).origin },
    data: {},
  });
  expect(unlisted.status()).toBe(404);

  const noToken = await page.request.post("/api/auth/login", {
    headers: { origin: "http://localhost:3100", "content-type": "application/json" },
    data: { email: "a@example.com", password: "x" },
  });
  expect(noToken.status()).toBe(403);
  expect((await noToken.json()).code).toBe("CSRF_REJECTED");
});

test("auth pages have no serious accessibility violations", async ({ page }) => {
  for (const path of ["/login", "/signup", "/check-email", "/forgot-password"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expectNoSeriousA11yViolations(page);
  }
});

test("the profile page has no serious accessibility violations", async ({ page }) => {
  const email = uniqueEmail();
  await createVerifiedAccount(page, email);
  await signIn(page, email, PASSWORD);
  await expect(page).toHaveURL(/\/account$/);
  await expectNoSeriousA11yViolations(page);
});
