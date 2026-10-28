import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

const push = vi.fn();
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace, refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

import nextConfig from "../../../next.config";
import { LoginForm } from "../auth/LoginForm";
import { SignupForm } from "../auth/SignupForm";
import { AcceptInvitation } from "./AcceptInvitation";

const fetchMock = vi.fn();
const TOKEN = "T0kenValue_abcdefghijklmnopqrstuvwxyz0123456";
const STORAGE_KEY = "helm.invitation-token";

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  push.mockReset();
  replace.mockReset();
  window.sessionStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

function respond(status: number, body?: object, headers?: Record<string, string>) {
  fetchMock.mockImplementation(async (url: string) => {
    if (String(url).includes("/csrf")) {
      return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" });
    }
    return new Response(body ? JSON.stringify(body) : null, { status, headers });
  });
}

function openLink(hash = `#token=${TOKEN}`) {
  window.history.replaceState(null, "", `/invitations/accept${hash}`);
}

const problem = (code: string, status: number) => ({ code, status, title: "x", type: "about:blank" });

test("the fragment is removed from the address bar and the token waits in session storage, before any request", async () => {
  openLink();
  render(<AcceptInvitation signedIn />);

  await screen.findByRole("button", { name: "Accept invitation" });
  expect(window.location.hash).toBe("");
  expect(window.location.href).not.toContain(TOKEN);
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(TOKEN);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("the token is sent in the body of one accept request and nowhere else, then cleared", async () => {
  respond(200, { id: "11111111-1111-4111-8111-111111111111", name: "Acme Ltd", role: "VIEWER" });
  openLink();
  render(<AcceptInvitation signedIn />);

  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  expect((await screen.findByRole("status")).textContent).toBe("You joined Acme Ltd as Viewer.");
  expect(fetchMock.mock.calls[0][0]).toBe("/api/auth/csrf");
  const [url, init] = fetchMock.mock.calls[1];
  expect(url).toBe("/api/auth/invitations/accept");
  expect(JSON.parse(init.body)).toEqual({ token: TOKEN });
  expect(JSON.stringify(fetchMock.mock.calls.map((call) => call[0]))).not.toContain(TOKEN);
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
});

test("the company name is not shown before the person accepts", async () => {
  openLink();
  render(<AcceptInvitation signedIn />);
  await screen.findByRole("button", { name: "Accept invitation" });
  expect(screen.getByText("Join this company?")).toBeTruthy();
});

test("signed out, the person is sent to sign in or sign up and comes back to this page", async () => {
  openLink();
  render(<AcceptInvitation signedIn={false} />);

  expect((await screen.findByRole("link", { name: "Sign in" })).getAttribute("href")).toBe("/login?next=invitation");
  expect(screen.getByRole("link", { name: "Create an account" }).getAttribute("href")).toBe("/signup?next=invitation");
  expect(screen.getByText(/Use the address this invitation was sent to/)).toBeTruthy();
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(TOKEN);
});

test("coming back from signing in, the token is read from session storage", async () => {
  window.sessionStorage.setItem(STORAGE_KEY, TOKEN);
  window.history.replaceState(null, "", "/invitations/accept");
  respond(200, { id: "11111111-1111-4111-8111-111111111111", name: "Acme", role: "ADMIN" });
  render(<AcceptInvitation signedIn />);

  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));
  await screen.findByText("You joined Acme as Admin.");
  expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ token: TOKEN });
});

test("a token in the query string is ignored", async () => {
  window.history.replaceState(null, "", `/invitations/accept?token=${TOKEN}`);
  render(<AcceptInvitation signedIn />);

  expect((await screen.findByRole("alert")).textContent).toMatch(/invalid or has expired/);
  expect(screen.queryByRole("button", { name: "Accept invitation" })).toBeNull();
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a link with no token, and no stored token, gives the invalid message", async () => {
  window.history.replaceState(null, "", "/invitations/accept");
  render(<AcceptInvitation signedIn={false} />);
  expect((await screen.findByRole("alert")).textContent).toMatch(/invalid or has expired/);
});

test("every unusable invitation shows the same message and clears the stored token", async () => {
  respond(422, problem("TOKEN_INVALID", 422));
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  expect((await screen.findByRole("alert")).textContent).toBe(
    "This invitation link is invalid or has expired. Ask the person who invited you to send a new one.",
  );
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
});

test("a conflict is explained without saying which of the two reasons it was", async () => {
  respond(409, problem("CONFLICT", 409));
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  expect((await screen.findByRole("alert")).textContent).toMatch(/already be a member.*not be accepting new members/);
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
});

test("a full company says the member limit is reached", async () => {
  respond(409, problem("MEMBER_LIMIT_REACHED", 409));
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  expect((await screen.findByRole("alert")).textContent).toMatch(/reached the member limit of its plan/);
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
});

test("when throttled the token is kept so the person can try again", async () => {
  respond(429, problem("RATE_LIMITED", 429), { "Retry-After": "600" });
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  expect((await screen.findByRole("alert")).textContent).toMatch(/Too many attempts.*10 minutes/);
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(TOKEN);
  expect((screen.getByRole("button", { name: "Accept invitation" }) as HTMLButtonElement).disabled).toBe(false);
});

test("a reply that cannot be read still tells the person they joined, as a success", async () => {
  fetchMock.mockImplementation(async (url: string) =>
    String(url).includes("/csrf")
      ? Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" })
      : new Response("not json", { status: 200 }),
  );
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  expect((await screen.findByRole("status")).textContent).toBe("You joined the company.");
  expect(screen.queryByRole("alert")).toBeNull();
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
});

test("an error with no text of its own still shows a message", async () => {
  respond(400, { ...problem("VALIDATION_FAILED", 400), errors: [{ field: "token", code: "REQUIRED" }] });
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  expect((await screen.findByRole("alert")).textContent).toBe("Something went wrong. Please try again.");
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(TOKEN);
});

test("an ended session sends the person to sign in again and back", async () => {
  respond(401, problem("UNAUTHENTICATED", 401));
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Accept invitation" }));

  await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?notice=expired&next=invitation"));
  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBe(TOKEN);
});

test("Not now discards the token and leaves", async () => {
  openLink();
  render(<AcceptInvitation signedIn />);
  fireEvent.click(await screen.findByRole("button", { name: "Not now" }));

  expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  expect(push).toHaveBeenCalledWith("/companies");
});

test("the page works when session storage is unavailable", async () => {
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("blocked");
  });
  openLink();
  render(<AcceptInvitation signedIn />);
  expect(await screen.findByRole("button", { name: "Accept invitation" })).toBeTruthy();
});

async function signInWith(search: string) {
  window.history.replaceState(null, "", `/login${search}`);
  render(<LoginForm />);
  fireEvent.change(screen.getByLabelText("Work email"), { target: { value: "a@example.com" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a password" } });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
}

test("sign in returns to the accept page only for the fixed invitation key", async () => {
  respond(200, {});
  await signInWith("?next=invitation");
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/invitations/accept"));
  cleanup();

  for (const next of ["https://evil.example", "/invitations/accept", "//evil.example", "/companies"]) {
    replace.mockReset();
    await signInWith(`?next=${encodeURIComponent(next)}`);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/companies"));
    cleanup();
  }
});

test("sign up from an invitation reminds the person which address to use", () => {
  window.history.replaceState(null, "", "/signup?next=invitation");
  render(<SignupForm />);
  expect(screen.getByRole("status").textContent).toBe("Use the address the invitation was sent to.");
  cleanup();

  window.history.replaceState(null, "", "/signup");
  render(<SignupForm />);
  expect(screen.queryByRole("status")).toBeNull();
});

test("the accept page is sent with no referrer and is never cached", async () => {
  const rules = await nextConfig.headers!();
  const headers = rules
    .filter((rule) => rule.source === "/invitations/accept" || rule.source === "/:path*")
    .flatMap((rule) => rule.headers);
  expect(headers).toContainEqual({ key: "Referrer-Policy", value: "no-referrer" });
  expect(headers).toContainEqual({ key: "Cache-Control", value: "no-store" });
});
