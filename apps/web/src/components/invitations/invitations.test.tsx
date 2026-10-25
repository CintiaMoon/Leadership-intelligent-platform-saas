import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

const push = vi.fn();
const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace, refresh }),
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

import { InvitationsList, type InvitationRow } from "./InvitationsList";
import { InviteButton } from "./InviteButton";

const fetchMock = vi.fn();
const COMPANY = "11111111-1111-4111-8111-111111111111";
const base = `/api/companies/${COMPANY}/invitations`;

const agentInvite: InvitationRow = { id: "i1", email: "ana@example.com", role: "AGENT", expires: "Nov 1, 2026", emailSent: true };
const adminInvite: InvitationRow = { id: "i2", email: "bo@example.com", role: "ADMIN", expires: "Nov 2, 2026", emailSent: false };

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

const problem = (code: string, status: number, extra: object = {}) => ({ code, status, title: "x", type: "about:blank", ...extra });

function calls(): string[] {
  return fetchMock.mock.calls
    .filter(([url]) => !String(url).includes("/csrf"))
    .map(([url, init]) => `${init?.method ?? "GET"} ${url}`);
}

function bodyOf(method: string, url: string) {
  const call = fetchMock.mock.calls.find(([u, init]) => u === url && init?.method === method)!;
  return JSON.parse(call[1].body);
}

function renderList(callerRole: string, invitations: InvitationRow[], companyStatus = "ACTIVE") {
  return render(
    <InvitationsList companyId={COMPANY} callerRole={callerRole} companyStatus={companyStatus} invitations={invitations} />,
  );
}

// ---- the list

test("each invitation shows address, role and expiry, and 'Sending' only until a link has been issued", () => {
  renderList("OWNER", [agentInvite, adminInvite]);
  const rows = screen.getAllByRole("listitem");
  expect(within(rows[0]).getByText("ana@example.com")).toBeTruthy();
  expect(within(rows[0]).getByText("Expires Nov 1, 2026")).toBeTruthy();
  expect(within(rows[0]).getByText("Agent")).toBeTruthy();
  expect(within(rows[0]).queryByText("Sending")).toBeNull();
  expect(within(rows[1]).getByText("Sending")).toBeTruthy();
});

test("the empty state, and no token or link anywhere", () => {
  renderList("OWNER", []);
  expect(screen.getByText("No pending invitations.")).toBeTruthy();
  cleanup();
  renderList("OWNER", [agentInvite]);
  expect(document.body.innerHTML).not.toMatch(/token|#token|invitations\/accept/i);
});

test("an Owner can resend and revoke every invitation; an Admin not those for Admins", () => {
  renderList("OWNER", [agentInvite, adminInvite]);
  expect(screen.getAllByRole("button", { name: /Resend|Revoke/ })).toHaveLength(4);
  cleanup();
  renderList("ADMIN", [agentInvite, adminInvite]);
  const rows = screen.getAllByRole("listitem");
  expect(within(rows[0]).getAllByRole("button")).toHaveLength(2);
  expect(within(rows[1]).queryByRole("button")).toBeNull();
});

test("a company that is being deleted offers no actions", () => {
  renderList("OWNER", [agentInvite], "PENDING_DELETION");
  expect(screen.queryByRole("button")).toBeNull();
});

test("addresses are text, never markup", () => {
  renderList("OWNER", [{ ...agentInvite, email: "<img src=x onerror=alert(1)>@example.com" }]);
  expect(document.querySelector("img")).toBeNull();
});

test("resending sends one POST, says so, and reads the list again", async () => {
  answer(`POST ${base}/i1/resend`, { status: 200, body: {} });
  renderList("OWNER", [agentInvite]);

  fireEvent.click(screen.getByRole("button", { name: "Resend the invitation to ana@example.com" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("The invitation to ana@example.com was sent again."));
  expect(calls()).toEqual([`POST ${base}/i1/resend`]);
  expect(refresh).toHaveBeenCalled();
});

test("revoking asks first, names the address, and only then sends a DELETE", async () => {
  answer(`DELETE ${base}/i1`, { status: 204 });
  renderList("OWNER", [agentInvite]);

  fireEvent.click(screen.getByRole("button", { name: "Revoke the invitation to ana@example.com" }));
  const dialog = await screen.findByRole("dialog", { name: "Revoke the invitation to ana@example.com?" });
  expect(calls()).toEqual([]);
  fireEvent.click(within(dialog).getByRole("button", { name: "Revoke" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("The invitation to ana@example.com was revoked."));
  expect(calls()).toEqual([`DELETE ${base}/i1`]);
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});

test("Cancel on the revoke confirmation sends nothing", async () => {
  renderList("OWNER", [agentInvite]);
  fireEvent.click(screen.getByRole("button", { name: /Revoke/ }));
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(calls()).toEqual([]);
});

test("an invitation that is no longer pending is explained and the list refreshes", async () => {
  answer(`POST ${base}/i1/resend`, { status: 409, body: problem("CONFLICT", 409) });
  renderList("OWNER", [agentInvite]);
  fireEvent.click(screen.getByRole("button", { name: /Resend/ }));
  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/invitation changed in the meantime/));
  expect(refresh).toHaveBeenCalled();
});

test("resending an Admin invitation with a stale login asks for the password and sends once more", async () => {
  answer(`POST ${base}/i2/resend`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) }, { status: 200, body: {} });
  answer("POST /api/auth/step-up", { status: 204 });
  renderList("OWNER", [adminInvite]);

  fireEvent.click(screen.getByRole("button", { name: /Resend/ }));
  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "my password 123" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/was sent again/));
  expect(calls()).toEqual([`POST ${base}/i2/resend`, "POST /api/auth/step-up", `POST ${base}/i2/resend`]);
});

// ---- the invite dialog

function openInvite(role = "OWNER", status = "ACTIVE", onPendingPage = false) {
  render(<InviteButton companyId={COMPANY} callerRole={role} companyStatus={status} onPendingPage={onPendingPage} />);
  fireEvent.click(screen.getByRole("button", { name: "Invite someone" }));
  return screen.findByRole("dialog", { name: "Invite someone" });
}

function fillAndSend(dialog: HTMLElement, email: string, role?: string) {
  fireEvent.change(within(dialog).getByLabelText("Email address"), { target: { value: email } });
  if (role) {
    fireEvent.change(within(dialog).getByLabelText("Role"), { target: { value: role } });
  }
  fireEvent.click(within(dialog).getByRole("button", { name: "Send invitation" }));
}

test("the button is not there for Agents, Viewers or a company that is being deleted", () => {
  for (const [role, status] of [["AGENT", "ACTIVE"], ["VIEWER", "ACTIVE"], ["OWNER", "PENDING_DELETION"]]) {
    render(<InviteButton companyId={COMPANY} callerRole={role} companyStatus={status} />);
    expect(screen.queryByRole("button", { name: "Invite someone" })).toBeNull();
    cleanup();
  }
});

test("the roles offered match the caller: an Owner may pick Admin, an Admin may not; the safest is the default", async () => {
  let dialog = await openInvite("OWNER");
  const ownerRoles = within(dialog).getByLabelText("Role") as HTMLSelectElement;
  expect([...ownerRoles.options].map((o) => o.text)).toEqual(["Viewer", "Agent", "Admin"]);
  expect(ownerRoles.value).toBe("VIEWER");
  cleanup();
  dialog = await openInvite("ADMIN");
  expect([...(within(dialog).getByLabelText("Role") as HTMLSelectElement).options].map((o) => o.text)).toEqual(["Viewer", "Agent"]);
});

test("sending posts the trimmed address and the role, closes, and says who it went to", async () => {
  answer(`POST ${base}`, { status: 201, body: { id: "i9", emailSent: false } });
  const dialog = await openInvite();

  fillAndSend(dialog, "  new.person@example.com ", "AGENT");

  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Invitation sent to new\.person@example\.com\./));
  expect(bodyOf("POST", base)).toEqual({ email: "new.person@example.com", role: "AGENT" });
  expect(screen.getByRole("link", { name: "View pending invitations" }).getAttribute("href")).toBe(`/companies/${COMPANY}/invitations`);
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(refresh).toHaveBeenCalled();
});

test("on the pending page the confirmation has no link to itself", async () => {
  answer(`POST ${base}`, { status: 201, body: {} });
  const dialog = await openInvite("OWNER", "ACTIVE", true);
  fillAndSend(dialog, "x@example.com");
  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Invitation sent to x@example\.com\./));
  expect(screen.queryByRole("link", { name: "View pending invitations" })).toBeNull();
});

test("an invalid address is shown against the field and the dialog stays open", async () => {
  answer(`POST ${base}`, { status: 400, body: problem("VALIDATION_FAILED", 400, { errors: [{ field: "email", code: "INVALID_EMAIL" }] }) });
  const dialog = await openInvite();

  fillAndSend(dialog, "not-an-address");

  const input = within(dialog).getByLabelText("Email address");
  await waitFor(() => expect(input.getAttribute("aria-invalid")).toBe("true"));
  expect(within(dialog).getByText("Enter a valid email address.")).toBeTruthy();
  expect((input as HTMLInputElement).value).toBe("not-an-address");
  expect(screen.getByRole("dialog", { name: "Invite someone" })).toBeTruthy();
});

test("a member or an existing invitation gets one message, and the limit says to wait", async () => {
  answer(`POST ${base}`, { status: 409, body: problem("CONFLICT", 409) });
  const dialog = await openInvite();
  fillAndSend(dialog, "dup@example.com");
  expect((await within(dialog).findByRole("alert")).textContent).toMatch(/already a member or already has a pending invitation/);
  cleanup();

  answer(`POST ${base}`, { status: 429, body: problem("RATE_LIMITED", 429), headers: { "Retry-After": "1800" } });
  const second = await openInvite();
  fillAndSend(second, "many@example.com");
  expect((await within(second).findByRole("alert")).textContent).toMatch(/too many invitations.*30 minutes/);
});

test("inviting an Admin with a stale login asks for the password, keeps the form, and sends once more", async () => {
  answer(`POST ${base}`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) }, { status: 201, body: {} });
  answer("POST /api/auth/step-up", { status: 204 });
  const dialog = await openInvite();

  fillAndSend(dialog, "admin.to.be@example.com", "ADMIN");

  const password = await screen.findByRole("dialog", { name: "Confirm your password" });
  expect((within(dialog).getByLabelText("Email address") as HTMLInputElement).value).toBe("admin.to.be@example.com");
  fireEvent.change(within(password).getByLabelText("Password"), { target: { value: "my password 123" } });
  fireEvent.click(within(password).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Invitation sent to admin\.to\.be@example\.com\./));
  expect(calls()).toEqual([`POST ${base}`, "POST /api/auth/step-up", `POST ${base}`]);
  expect(bodyOf("POST", base)).toEqual({ email: "admin.to.be@example.com", role: "ADMIN" });
});

test("cancelling the password dialog drops the invitation and keeps the form for another try", async () => {
  answer(`POST ${base}`, { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) });
  const dialog = await openInvite();
  fillAndSend(dialog, "keep@example.com", "ADMIN");

  const password = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.click(within(password).getByRole("button", { name: "Cancel" }));

  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Confirm your password" })).toBeNull());
  expect(calls()).toEqual([`POST ${base}`]);
  expect((within(screen.getByRole("dialog", { name: "Invite someone" })).getByLabelText("Email address") as HTMLInputElement).value).toBe(
    "keep@example.com",
  );
});

test("Cancel closes the invite dialog and clears it without sending", async () => {
  const dialog = await openInvite();
  fireEvent.change(within(dialog).getByLabelText("Email address"), { target: { value: "gone@example.com" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(calls()).toEqual([]);
  expect((document.querySelector('input[name="email"]') as HTMLInputElement).value).toBe("");
});

test("if the password is accepted but the invitation is still refused for it, there is no second prompt", async () => {
  answer(
    `POST ${base}`,
    { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) },
    { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) },
  );
  answer("POST /api/auth/step-up", { status: 204 });
  const dialog = await openInvite();
  fillAndSend(dialog, "again@example.com", "ADMIN");

  const password = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(password).getByLabelText("Password"), { target: { value: "right password" } });
  fireEvent.click(within(password).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(calls()).toEqual([`POST ${base}`, "POST /api/auth/step-up", `POST ${base}`]));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Confirm your password" })).toBeNull());
  expect(screen.getByRole("dialog", { name: "Invite someone" })).toBeTruthy();
});
