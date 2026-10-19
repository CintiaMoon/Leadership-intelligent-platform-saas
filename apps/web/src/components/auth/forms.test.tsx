import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

const push = vi.fn();
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace, refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

import { LoginForm } from "./LoginForm";
import { SignupForm } from "./SignupForm";

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  push.mockReset();
  replace.mockReset();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

/** Answers the CSRF fetch, then returns the given status and body for the real call. */
function respond(status: number, body?: object) {
  fetchMock.mockImplementation(async (url: string) => {
    if (String(url).includes("/csrf")) {
      return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" });
    }
    return new Response(body ? JSON.stringify(body) : null, { status });
  });
}

function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

test("login sends the CSRF header, then goes to the account page", async () => {
  respond(200, {});
  render(<LoginForm />);
  fill("Work email", "a@example.com");
  fill("Password", "a password");
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

  await waitFor(() => expect(replace).toHaveBeenCalledWith("/account"));
  const [csrfUrl] = fetchMock.mock.calls[0];
  expect(csrfUrl).toBe("/api/auth/csrf/pre-login");
  const [, init] = fetchMock.mock.calls[1];
  expect(init.headers["X-CSRF-TOKEN"]).toBe("csrf-token");
  expect(JSON.parse(init.body)).toEqual({ email: "a@example.com", password: "a password" });
});

test("a failed login shows one fixed message and keeps what was typed", async () => {
  respond(401, { code: "INVALID_CREDENTIALS", status: 401, title: "x", type: "about:blank" });
  render(<LoginForm />);
  fill("Work email", "a@example.com");
  fill("Password", "wrong");
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

  expect((await screen.findByRole("alert")).textContent).toMatch(/email or password is incorrect/);
  expect((screen.getByLabelText("Work email") as HTMLInputElement).value).toBe("a@example.com");
  expect(replace).not.toHaveBeenCalled();
});

test("the login page shows a notice only for the fixed keys", () => {
  window.history.replaceState(null, "", "/login?notice=expired");
  render(<LoginForm />);
  expect(screen.getByRole("status").textContent).toMatch(/session has ended/);
  cleanup();

  window.history.replaceState(null, "", "/login?notice=%3Cb%3Eoops%3C%2Fb%3E");
  render(<LoginForm />);
  expect(screen.queryByRole("status")).toBeNull();
});

test("signup shows a server field error against its field and does not navigate", async () => {
  respond(400, {
    code: "VALIDATION_FAILED",
    status: 400,
    title: "x",
    type: "about:blank",
    errors: [{ field: "password", code: "BREACHED_PASSWORD" }],
  });
  render(<SignupForm />);
  fill("Full name", "Alex Morgan");
  fill("Work email", "alex@example.com");
  fill("Password", "password123");
  fireEvent.click(screen.getByRole("button", { name: "Create account" }));

  const input = screen.getByLabelText("Password");
  await waitFor(() => expect(input.getAttribute("aria-invalid")).toBe("true"));
  const errorId = input.getAttribute("aria-describedby")!.split(" ").pop()!;
  expect(document.getElementById(errorId)!.textContent).toMatch(/known data breaches/);
  expect(push).not.toHaveBeenCalled();
});

test("a successful signup moves to the check-your-email page", async () => {
  respond(202, { message: "ok" });
  render(<SignupForm />);
  fill("Full name", "Alex Morgan");
  fill("Work email", "alex@example.com");
  fill("Password", "a long passphrase here");
  fireEvent.click(screen.getByRole("button", { name: "Create account" }));
  await waitFor(() => expect(push).toHaveBeenCalledWith("/check-email"));
});
