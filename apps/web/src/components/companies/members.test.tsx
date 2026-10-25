import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

const push = vi.fn();
const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace, refresh }),
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

import { CompanyHeader } from "./CompanyHeader";
import { MembersList, type MemberRow } from "./MembersList";

const fetchMock = vi.fn();
const COMPANY = "11111111-1111-4111-8111-111111111111";
const base = `/api/companies/${COMPANY}/members`;

const owner: MemberRow = { id: "a1", displayName: "Ana Silva", email: "ana@example.com", role: "OWNER", status: "ACTIVE", joined: "Oct 12, 2026", self: true };
const admin: MemberRow = { id: "b2", displayName: "Bo Chen", email: "bo@example.com", role: "ADMIN", status: "ACTIVE", joined: "Oct 14, 2026", self: false };
const agent: MemberRow = { id: "c3", displayName: "Cy Dube", email: "cy@example.com", role: "AGENT", status: "ACTIVE", joined: "Oct 15, 2026", self: false };
const suspended: MemberRow = { id: "d4", displayName: "Di Eze", email: "di@example.com", role: "VIEWER", status: "SUSPENDED", joined: "Oct 16, 2026", self: false };

type Answer = { status: number; body?: object; headers?: Record<string, string> };
let queue: Record<string, Answer[]>;

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  push.mockReset();
  replace.mockReset();
  refresh.mockReset();
  queue = {};
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (String(url).includes("/csrf")) {
      return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" });
    }
    const key = `${init?.method ?? "GET"} ${url}`;
    const next = queue[key]?.shift();
    if (!next) {
      throw new Error(`unexpected request ${key}`);
    }
    return new Response(next.body ? JSON.stringify(next.body) : null, { status: next.status, headers: next.headers });
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function answer(key: string, ...answers: Answer[]) {
  queue[key] = answers;
}

const problem = (code: string, status: number) => ({ code, status, title: "x", type: "about:blank" });

/** The requests that were not CSRF lookups, as "METHOD url". */
function calls(): string[] {
  return fetchMock.mock.calls
    .filter(([url]) => !String(url).includes("/csrf"))
    .map(([url, init]) => `${init?.method ?? "GET"} ${url}`);
}

function renderList(callerRole: string, members: MemberRow[], companyStatus = "ACTIVE") {
  return render(<MembersList companyId={COMPANY} callerRole={callerRole} companyStatus={companyStatus} members={members} />);
}

// ---- what is shown

test("each person shows name, address, role, status and join date; your own row is marked", () => {
  renderList("OWNER", [owner, admin, suspended]);
  const rows = screen.getAllByRole("listitem");
  expect(rows).toHaveLength(3);
  expect(within(rows[0]).getByText("(you)")).toBeTruthy();
  expect(within(rows[1]).getByText("bo@example.com")).toBeTruthy();
  expect(within(rows[1]).getByText("Joined Oct 14, 2026")).toBeTruthy();
  expect(within(rows[2]).getByText("Suspended")).toBeTruthy();
});

test("an Owner sees controls for others but none on their own row", () => {
  renderList("OWNER", [owner, admin, agent, suspended]);
  const rows = screen.getAllByRole("listitem");
  expect(within(rows[0]).queryByRole("button")).toBeNull();
  expect(within(rows[0]).queryByRole("combobox")).toBeNull();
  const roles = within(rows[1]).getByRole("combobox", { name: "Role of Bo Chen" });
  expect([...(roles as HTMLSelectElement).options].map((o) => o.text)).toEqual(["Admin", "Agent", "Viewer"]);
  expect(within(rows[2]).getByRole("button", { name: "Suspend Cy Dube" })).toBeTruthy();
  expect(within(rows[3]).getByRole("button", { name: "Reactivate Di Eze" })).toBeTruthy();
  expect(within(rows[3]).queryByRole("combobox")).toBeNull();
});

test("an Admin has no controls on the Owner or another Admin, and only Agent and Viewer to choose from", () => {
  renderList("ADMIN", [{ ...owner, self: false }, { ...admin, self: true }, { ...admin, id: "e5", displayName: "Ed Fox", self: false }, agent]);
  const rows = screen.getAllByRole("listitem");
  for (const row of rows.slice(0, 3)) {
    expect(within(row).queryByRole("button")).toBeNull();
    expect(within(row).queryByRole("combobox")).toBeNull();
  }
  const roles = within(rows[3]).getByRole("combobox") as HTMLSelectElement;
  expect([...roles.options].map((o) => o.text)).toEqual(["Agent", "Viewer"]);
});

test("a company that is being deleted offers no actions", () => {
  renderList("OWNER", [owner, agent], "PENDING_DELETION");
  expect(screen.queryByRole("button")).toBeNull();
  expect(screen.queryByRole("combobox")).toBeNull();
});

test("names and addresses are text, never markup", () => {
  renderList("OWNER", [owner, { ...agent, displayName: "<img src=x onerror=alert(1)>" }]);
  expect(document.querySelector("img")).toBeNull();
  expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeTruthy();
});

test("the tab row is only for people who can manage members", () => {
  render(<CompanyHeader companyId={COMPANY} name="Acme" role="ADMIN" active="members" />);
  expect(screen.getByRole("link", { name: "Members" }).getAttribute("aria-current")).toBe("page");
  cleanup();
  render(<CompanyHeader companyId={COMPANY} name="Acme" role="VIEWER" />);
  expect(screen.queryByRole("navigation", { name: "Company" })).toBeNull();
});

// ---- actions

test("changing a role sends one PATCH with the new role, says so, and reads the list again", async () => {
  answer(`PATCH ${base}/c3`, { status: 200, body: { id: "c3" } });
  renderList("OWNER", [owner, agent]);

  fireEvent.change(screen.getByRole("combobox", { name: "Role of Cy Dube" }), { target: { value: "VIEWER" } });

  expect((await screen.findByRole("status")).textContent).toBe("Cy Dube is now a viewer.");
  expect(calls()).toEqual([`PATCH ${base}/c3`]);
  expect(JSON.parse(fetchMock.mock.calls.find(([, init]) => init?.method === "PATCH")![1].body)).toEqual({ role: "VIEWER" });
  expect(refresh).toHaveBeenCalled();
});

test("suspending asks first, names the person, and only then sends", async () => {
  answer(`POST ${base}/c3/suspend`, { status: 200, body: {} });
  renderList("OWNER", [owner, agent]);

  fireEvent.click(screen.getByRole("button", { name: "Suspend Cy Dube" }));
  const dialog = await screen.findByRole("dialog", { name: "Suspend Cy Dube?" });
  expect(within(dialog).getByText(/reactivate them later/)).toBeTruthy();
  expect(calls()).toEqual([]);
  fireEvent.click(within(dialog).getByRole("button", { name: "Suspend" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Cy Dube was suspended."));
  expect(calls()).toEqual([`POST ${base}/c3/suspend`]);
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});

test("removing says they will need a new invitation, and sends a DELETE", async () => {
  answer(`DELETE ${base}/c3`, { status: 204 });
  renderList("ADMIN", [{ ...owner, self: false }, { ...admin, self: true }, agent]);

  fireEvent.click(screen.getByRole("button", { name: "Remove Cy Dube" }));
  const dialog = await screen.findByRole("dialog", { name: "Remove Cy Dube?" });
  expect(within(dialog).getByText(/need a new invitation to rejoin/)).toBeTruthy();
  fireEvent.click(within(dialog).getByRole("button", { name: "Remove" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Cy Dube was removed."));
  expect(calls()).toEqual([`DELETE ${base}/c3`]);
});

test("Cancel on a confirmation sends nothing", async () => {
  renderList("OWNER", [owner, agent]);
  fireEvent.click(screen.getByRole("button", { name: "Remove Cy Dube" }));
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(calls()).toEqual([]);
});

test("reactivating sends one POST at once", async () => {
  answer(`POST ${base}/d4/reactivate`, { status: 200, body: {} });
  renderList("OWNER", [owner, suspended]);

  fireEvent.click(screen.getByRole("button", { name: "Reactivate Di Eze" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Di Eze was reactivated."));
  expect(calls()).toEqual([`POST ${base}/d4/reactivate`]);
});

// ---- refusals

test("a conflict says the member changed, closes the dialog and reads the list again", async () => {
  answer(`POST ${base}/c3/suspend`, { status: 409, body: problem("CONFLICT", 409) });
  renderList("OWNER", [owner, agent]);
  fireEvent.click(screen.getByRole("button", { name: "Suspend Cy Dube" }));
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Suspend" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/changed in the meantime/));
  expect(refresh).toHaveBeenCalled();
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});

test("a 403 or 404 says it is not available any more; 429 and 503 say to wait and keep the dialog", async () => {
  answer(`PATCH ${base}/c3`, { status: 404, body: problem("NOT_FOUND", 404) });
  renderList("OWNER", [owner, agent]);
  fireEvent.change(screen.getByRole("combobox", { name: "Role of Cy Dube" }), { target: { value: "ADMIN" } });
  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/not available to you any more/));
  cleanup();

  answer(`DELETE ${base}/c3`, { status: 503, body: problem("SERVICE_UNAVAILABLE", 503), headers: { "Retry-After": "2" } });
  renderList("OWNER", [owner, agent]);
  fireEvent.click(screen.getByRole("button", { name: "Remove Cy Dube" }));
  const dialog = await screen.findByRole("dialog");
  fireEvent.click(within(dialog).getByRole("button", { name: "Remove" }));
  expect((await within(dialog).findByRole("alert")).textContent).toMatch(/busy right now/);
  expect(screen.getByRole("dialog")).toBeTruthy();
});

test("an ended session goes to sign in", async () => {
  answer(`POST ${base}/d4/reactivate`, { status: 401, body: problem("UNAUTHENTICATED", 401) });
  renderList("OWNER", [owner, suspended]);
  fireEvent.click(screen.getByRole("button", { name: "Reactivate Di Eze" }));
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?notice=expired"));
});

// ---- step-up

function promote() {
  fireEvent.change(screen.getByRole("combobox", { name: "Role of Cy Dube" }), { target: { value: "ADMIN" } });
}

test("a stale login asks for the password, then sends the same request once more", async () => {
  answer(`PATCH ${base}/c3`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) }, { status: 200, body: {} });
  answer("POST /api/auth/step-up", { status: 204 });
  renderList("OWNER", [owner, agent]);

  promote();
  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  expect(calls()).toEqual([`PATCH ${base}/c3`]);
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "my password 123" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Cy Dube is now an admin."));
  expect(calls()).toEqual([`PATCH ${base}/c3`, "POST /api/auth/step-up", `PATCH ${base}/c3`]);
  const stepUp = fetchMock.mock.calls.find(([url]) => url === "/api/auth/step-up")!;
  expect(JSON.parse(stepUp[1].body)).toEqual({ password: "my password 123" });
  expect(JSON.stringify(fetchMock.mock.calls.filter(([url]) => url !== "/api/auth/step-up").map((c) => c[1]?.body ?? ""))).not.toContain(
    "my password",
  );
});

test("a wrong password shows the sign-in message, keeps the dialog and does not retry", async () => {
  answer(`PATCH ${base}/c3`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) });
  answer("POST /api/auth/step-up", { status: 401, body: problem("INVALID_CREDENTIALS", 401) });
  renderList("OWNER", [owner, agent]);

  promote();
  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "wrong password" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));

  expect((await within(dialog).findByRole("alert")).textContent).toBe("That password is not correct.");
  expect(calls()).toEqual([`PATCH ${base}/c3`, "POST /api/auth/step-up"]);
  expect(screen.getByRole("dialog", { name: "Confirm your password" })).toBeTruthy();
  expect((within(dialog).getByLabelText("Password") as HTMLInputElement).value).toBe("");
});

test("cancelling the password dialog drops the action and clears the field", async () => {
  answer(`PATCH ${base}/c3`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) });
  renderList("OWNER", [owner, agent]);

  promote();
  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "typed but not sent" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(calls()).toEqual([`PATCH ${base}/c3`]);
  expect((document.querySelector('input[type="password"]') as HTMLInputElement).value).toBe("");
});

test("if the password is accepted but the request still needs it, there is no second prompt", async () => {
  answer(
    `PATCH ${base}/c3`,
    { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) },
    { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) },
  );
  answer("POST /api/auth/step-up", { status: 204 });
  renderList("OWNER", [owner, agent]);

  promote();
  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "right password" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/password is needed again/));
  expect(screen.getByRole("status").textContent).not.toMatch(/not available/);
  expect(calls()).toEqual([`PATCH ${base}/c3`, "POST /api/auth/step-up", `PATCH ${base}/c3`]);
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(refresh).not.toHaveBeenCalled();
});

// ---- review fixes (F1-5)

test("a password refused for a reason other than being wrong means the session ended: go to sign in", async () => {
  answer(`PATCH ${base}/c3`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) });
  answer("POST /api/auth/step-up", { status: 401, body: problem("UNAUTHENTICATED", 401) });
  renderList("OWNER", [owner, agent]);

  promote();
  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "some password" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?notice=expired"));
  expect(calls()).toEqual([`PATCH ${base}/c3`, "POST /api/auth/step-up"]);
});

test("the password field is empty after a successful confirmation too", async () => {
  answer(`PATCH ${base}/c3`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) }, { status: 200, body: {} });
  answer("POST /api/auth/step-up", { status: 204 });
  renderList("OWNER", [owner, agent]);
  promote();
  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  const field = within(dialog).getByLabelText("Password") as HTMLInputElement;
  fireEvent.change(field, { target: { value: "my password 123" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));
  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/is now an admin/));
  expect(field.value).toBe("");
});

test("a request the BFF refuses as unverified says so, and does not claim the member is gone", async () => {
  answer(`PATCH ${base}/c3`, { status: 403, body: problem("CSRF_REJECTED", 403) });
  renderList("OWNER", [owner, agent]);
  fireEvent.change(screen.getByRole("combobox", { name: "Role of Cy Dube" }), { target: { value: "VIEWER" } });
  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/could not be verified/));
  expect(screen.getByRole("status").textContent).not.toMatch(/not available/);
  expect(refresh).not.toHaveBeenCalled();
});

test("the rate limit on Members says to wait", async () => {
  answer(`POST ${base}/d4/reactivate`, { status: 429, body: problem("RATE_LIMITED", 429), headers: { "Retry-After": "120" } });
  renderList("OWNER", [owner, suspended]);
  fireEvent.click(screen.getByRole("button", { name: "Reactivate Di Eze" }));
  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Too many attempts.*2 minutes/));
});

test("a failed role change leaves the select showing the role the member still has", async () => {
  answer(`PATCH ${base}/c3`, { status: 409, body: problem("CONFLICT", 409) });
  renderList("OWNER", [owner, agent]);
  const select = screen.getByRole("combobox", { name: "Role of Cy Dube" }) as HTMLSelectElement;

  fireEvent.change(select, { target: { value: "ADMIN" } });

  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/changed in the meantime/));
  expect(select.value).toBe("AGENT");
});

test("two quick clicks send one request, and the busy control stays focusable (aria-disabled, not disabled)", async () => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  fetchMock.mockImplementation(async (url: string) => {
    if (String(url).includes("/csrf")) {
      return Response.json({ token: "csrf-token" });
    }
    await gate;
    return new Response("{}", { status: 200 });
  });
  renderList("OWNER", [owner, suspended]);
  const button = screen.getByRole("button", { name: "Reactivate Di Eze" });

  button.focus();
  fireEvent.click(button);
  fireEvent.click(button);
  await waitFor(() => expect(button.getAttribute("aria-disabled")).toBe("true"));
  expect((button as HTMLButtonElement).disabled).toBe(false);
  expect(document.activeElement).toBe(button);
  release();

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Di Eze was reactivated."));
  expect(calls()).toEqual([`POST ${base}/d4/reactivate`]);
});

test("when the row that had focus disappears after a change, focus moves to the message", async () => {
  answer(`DELETE ${base}/c3`, { status: 204 });
  const { rerender } = renderList("OWNER", [owner, agent]);
  fireEvent.click(screen.getByRole("button", { name: "Remove Cy Dube" }));
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));
  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Cy Dube was removed."));

  rerender(<MembersList companyId={COMPANY} callerRole="OWNER" companyStatus="ACTIVE" members={[owner]} />);

  await waitFor(() => expect(document.activeElement).toBe(screen.getByRole("status")));
});
