import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

const push = vi.fn();
const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace, refresh }),
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

import { AppHeader } from "../app/AppHeader";
import { CompanyList } from "./CompanyList";
import { CreateCompanyForm } from "./CreateCompanyForm";
import { LeaveCompany } from "./LeaveCompany";
import { NotAvailable } from "./NotAvailable";

const fetchMock = vi.fn();
const ID = "11111111-1111-4111-8111-111111111111";

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  push.mockReset();
  replace.mockReset();
  refresh.mockReset();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function respond(status: number, body?: object, headers?: Record<string, string>) {
  fetchMock.mockImplementation(async (url: string) =>
    String(url).includes("/csrf")
      ? Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" })
      : new Response(body ? JSON.stringify(body) : null, { status, headers }),
  );
}

const problem = (code: string, status: number, extra: object = {}) => ({
  code,
  status,
  title: "x",
  type: "about:blank",
  ...extra,
});

// ---- list and header

test("the list shows each company with its role and an Open link, or the empty state", () => {
  render(
    <CompanyList
      companies={[
        { id: ID, name: "Acme Labs", role: "OWNER" },
        { id: "22222222-2222-4222-8222-222222222222", name: "Bo's Studio", role: "VIEWER" },
      ]}
    />,
  );
  expect(screen.getByText("Acme Labs")).toBeTruthy();
  expect(screen.getByText("Owner")).toBeTruthy();
  expect(screen.getByText("Viewer")).toBeTruthy();
  expect(screen.getByRole("link", { name: "Open Acme Labs" }).getAttribute("href")).toBe(`/companies/${ID}`);
  cleanup();

  render(<CompanyList companies={[]} />);
  expect(screen.getByText(/do not belong to any company yet/)).toBeTruthy();
  expect(screen.queryByRole("link")).toBeNull();
});

test("a company name is rendered as text, never as markup", () => {
  render(<CompanyList companies={[{ id: ID, name: "<img src=x onerror=alert(1)>", role: "AGENT" }]} />);
  expect(document.querySelector("img")).toBeNull();
  expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeTruthy();
});

test("the header has the product name, My companies, the profile link with the person's name and Sign out", () => {
  render(<AppHeader displayName="Alex Morgan" />);
  expect(screen.getByRole("link", { name: /Helm/ }).getAttribute("href")).toBe("/companies");
  expect(within(screen.getByRole("navigation", { name: "Main" })).getByRole("link", { name: "My companies" })).toBeTruthy();
  expect(screen.getByRole("link", { name: "Alex Morgan" }).getAttribute("href")).toBe("/account");
  expect(screen.getByRole("button", { name: "Sign out" })).toBeTruthy();
});

test("signing out removes an invitation token kept in this tab", async () => {
  window.sessionStorage.setItem("helm.invitation-token", "waiting-token");
  respond(204);
  render(<AppHeader displayName="Alex Morgan" />);

  fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

  await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
  expect(window.sessionStorage.getItem("helm.invitation-token")).toBeNull();
});

test("the not-available state says the same thing for every reason and links back", () => {
  render(<NotAvailable />);
  expect(screen.getByRole("heading", { name: "This page is not available." })).toBeTruthy();
  expect(screen.getByRole("link", { name: "Go to my companies" }).getAttribute("href")).toBe("/companies");
});

// ---- create

function typeName(value: string) {
  fireEvent.change(screen.getByLabelText("Company name"), { target: { value } });
  fireEvent.click(screen.getByRole("button", { name: "Create company" }));
}

test("creating a company sends only the name with the CSRF token and opens the new company", async () => {
  respond(201, { id: ID, name: "Acme", role: "OWNER" });
  render(<CreateCompanyForm />);

  typeName("Acme");

  await waitFor(() => expect(push).toHaveBeenCalledWith(`/companies/${ID}`));
  const [csrfUrl] = fetchMock.mock.calls[0];
  expect(csrfUrl).toBe("/api/auth/csrf");
  const [url, init] = fetchMock.mock.calls[1];
  expect(url).toBe("/api/companies");
  expect(init.method).toBe("POST");
  expect(init.headers["X-CSRF-TOKEN"]).toBe("csrf-token");
  expect(JSON.parse(init.body)).toEqual({ name: "Acme" });
});

test("a server field error is shown against the name field and nothing navigates", async () => {
  respond(400, problem("VALIDATION_FAILED", 400, { errors: [{ field: "name", code: "INVALID" }] }));
  render(<CreateCompanyForm />);

  typeName("bad‮name");

  const input = screen.getByLabelText("Company name");
  await waitFor(() => expect(input.getAttribute("aria-invalid")).toBe("true"));
  expect(push).not.toHaveBeenCalled();
  expect((input as HTMLInputElement).value).toBe("bad‮name");
});

test("the creation limit and an unavailable service give a wait message", async () => {
  respond(429, problem("RATE_LIMITED", 429), { "Retry-After": "3600" });
  render(<CreateCompanyForm />);
  typeName("Acme");
  expect((await screen.findByRole("alert")).textContent).toMatch(/Too many attempts.*about 60 minutes/);
  cleanup();

  respond(503, problem("SERVICE_UNAVAILABLE", 503));
  render(<CreateCompanyForm />);
  typeName("Acme");
  expect((await screen.findByRole("alert")).textContent).toMatch(/busy right now/);
});

test("an ended session while creating goes to sign in", async () => {
  respond(401, problem("UNAUTHENTICATED", 401));
  render(<CreateCompanyForm />);
  typeName("Acme");
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?notice=expired"));
});

// ---- leave

test("an Owner is told to transfer ownership first and has no leave button", () => {
  render(<LeaveCompany companyId={ID} companyName="Acme" role="OWNER" />);
  expect(screen.getByText(/Transfer ownership first/)).toBeTruthy();
  expect(screen.queryByRole("button")).toBeNull();
});

test("leaving asks first, names the company, and only then sends the request", async () => {
  respond(204);
  render(<LeaveCompany companyId={ID} companyName="Acme Labs" role="VIEWER" />);

  fireEvent.click(screen.getByRole("button", { name: "Leave company" }));
  const dialog = await screen.findByRole("dialog", { name: "Leave Acme Labs?" });
  expect(within(dialog).getByText(/lose access immediately/)).toBeTruthy();
  expect(fetchMock).not.toHaveBeenCalled();

  fireEvent.click(within(dialog).getByRole("button", { name: "Leave company" }));

  await waitFor(() => expect(replace).toHaveBeenCalledWith("/companies"));
  const [url, init] = fetchMock.mock.calls[1];
  expect(url).toBe(`/api/companies/${ID}/leave`);
  expect(init.method).toBe("POST");
  expect(refresh).toHaveBeenCalled();
});

test("Cancel closes the confirmation without sending anything", async () => {
  render(<LeaveCompany companyId={ID} companyName="Acme" role="AGENT" />);
  fireEvent.click(screen.getByRole("button", { name: "Leave company" }));
  const dialog = await screen.findByRole("dialog");

  fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a refusal is shown inside the dialog, which stays open", async () => {
  respond(403, problem("FORBIDDEN", 403));
  render(<LeaveCompany companyId={ID} companyName="Acme" role="ADMIN" />);
  fireEvent.click(screen.getByRole("button", { name: "Leave company" }));
  const dialog = await screen.findByRole("dialog");

  fireEvent.click(within(dialog).getByRole("button", { name: "Leave company" }));

  expect((await within(dialog).findByRole("alert")).textContent).toMatch(/not available to you/);
  expect(replace).not.toHaveBeenCalled();
  expect(screen.getByRole("dialog")).toBeTruthy();
});
