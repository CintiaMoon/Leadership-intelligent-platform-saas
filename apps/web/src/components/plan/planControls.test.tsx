import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh }),
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

import { InvitationsList, type InvitationRow } from "@/components/invitations/InvitationsList";
import { InviteButton } from "@/components/invitations/InviteButton";
import { MembersList, type MemberRow } from "@/components/companies/MembersList";
import { OwnershipPanel } from "@/components/ownership/OwnershipPanel";
import type { TransferView } from "@/lib/ownership";
import { MEMBER_LIMIT_MESSAGE, PLAN_RESTRICTED_MESSAGE } from "@/lib/api/messages";
import { toPlanView, type PlanView, type SubscriptionState } from "@/lib/plan";

import { PLAN_STATUS_ID } from "./PlanBanner";

/**
 * The controls a subscription state turns off (plan Q-19): they stay on the page, say they are not available, point at
 * the reason, and send nothing. What the API refuses anyway is explained from its stable code.
 */

const COMPANY = "11111111-1111-4111-8111-111111111111";
const fetchMock = vi.fn();

const owner: MemberRow = { id: "a1", displayName: "Ana Silva", email: "ana@example.com", role: "OWNER", status: "ACTIVE", joined: "Oct 12, 2026", self: true };
const agent: MemberRow = { id: "c3", displayName: "Cy Dube", email: "cy@example.com", role: "AGENT", status: "ACTIVE", joined: "Oct 15, 2026", self: false };
const suspended: MemberRow = { id: "d4", displayName: "Di Eze", email: "di@example.com", role: "VIEWER", status: "SUSPENDED", joined: "Oct 16, 2026", self: false };
const invite: InvitationRow = { id: "i1", email: "ana2@example.com", role: "AGENT", expires: "Nov 1, 2026", emailSent: true };

function plan(state: SubscriptionState, usage = 4): PlanView {
  return toPlanView({
    plan: { code: "TEAM", name: "Team", version: 1 },
    state,
    trialEndsAt: null,
    readOnlyEndsAt: null,
    features: [],
    limits: [{ key: "ACTIVE_MEMBERS", value: 15, enforced: true, usage }],
  });
}

let answers: Array<{ status: number; body?: object }>;

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  refresh.mockReset();
  answers = [];
  fetchMock.mockImplementation(async (url: string) => {
    if (String(url).includes("/csrf")) {
      return Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" });
    }
    const next = answers.shift();
    if (!next) {
      throw new Error(`unexpected request ${url}`);
    }
    return new Response(next.body ? JSON.stringify(next.body) : null, { status: next.status });
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function sent(): number {
  return fetchMock.mock.calls.filter(([url]) => !String(url).includes("/csrf")).length;
}

function members(state: SubscriptionState | null, usage = 4, rows: MemberRow[] = [owner, agent, suspended]) {
  return render(
    <>
      {state ? <p id={PLAN_STATUS_ID}>banner</p> : null}
      <MembersList
        companyId={COMPANY}
        callerRole="OWNER"
        companyStatus="ACTIVE"
        members={rows}
        plan={state ? plan(state, usage) : null}
      />
    </>,
  );
}

const problem = (code: string, status: number) => ({ code, status, title: "x", type: "about:blank" });

// ---- members

test("while read-only, role changes and reactivation are off with the reason, and suspend and remove still work", async () => {
  members("EXPIRED_READ_ONLY");
  const role = screen.getByRole("combobox", { name: "Role of Cy Dube" }) as HTMLSelectElement;
  expect(role.disabled).toBe(true);
  expect(role.getAttribute("aria-describedby")).toBe(PLAN_STATUS_ID);
  const reactivate = screen.getByRole("button", { name: "Reactivate Di Eze" });
  expect(reactivate.getAttribute("aria-disabled")).toBe("true");
  expect(reactivate.getAttribute("aria-describedby")).toBe(PLAN_STATUS_ID);

  fireEvent.click(reactivate);
  expect(sent()).toBe(0);

  const suspend = screen.getByRole("button", { name: "Suspend Cy Dube" });
  expect(suspend.getAttribute("aria-disabled")).toBeNull();
  answers.push({ status: 200, body: {} });
  fireEvent.click(suspend);
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Suspend" }));
  await waitFor(() => expect(sent()).toBe(1));
});

test("once the company is locked nothing can be changed, and every refused control points at the banner", () => {
  members("EXPIRED_LOCKED");
  for (const name of ["Suspend Cy Dube", "Remove Cy Dube", "Reactivate Di Eze"]) {
    const button = screen.getByRole("button", { name });
    expect(button.getAttribute("aria-disabled"), name).toBe("true");
    expect(button.getAttribute("aria-describedby"), name).toBe(PLAN_STATUS_ID);
    fireEvent.click(button);
  }
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(sent()).toBe(0);
});

test("a trial or an active plan below the limit leaves every control working", () => {
  for (const state of ["TRIALING", "ACTIVE"] as const) {
    const { unmount } = members(state);
    expect((screen.getByRole("combobox", { name: "Role of Cy Dube" }) as HTMLSelectElement).disabled).toBe(false);
    expect(screen.getByRole("button", { name: "Reactivate Di Eze" }).getAttribute("aria-disabled")).toBeNull();
    expect(screen.queryByText(/member limit is reached/)).toBeNull();
    unmount();
  }
});

test("at the member limit only reactivation is off, with the limit as the reason", () => {
  members("ACTIVE", 15);
  const reactivate = screen.getByRole("button", { name: "Reactivate Di Eze" });
  expect(reactivate.getAttribute("aria-disabled")).toBe("true");
  const note = screen.getByText(/member limit is reached/);
  expect(reactivate.getAttribute("aria-describedby")).toBe(note.id);
  expect(screen.getByRole("button", { name: "Suspend Cy Dube" }).getAttribute("aria-disabled")).toBeNull();
  fireEvent.click(reactivate);
  expect(sent()).toBe(0);
});

test("the limit note is only there when someone could be reactivated", () => {
  members("ACTIVE", 15, [owner, agent]);
  expect(screen.queryByText(/member limit is reached/)).toBeNull();
});

test("a plan that cannot be read turns nothing off: the API decides", () => {
  members(null);
  expect(screen.getByRole("button", { name: "Reactivate Di Eze" }).getAttribute("aria-disabled")).toBeNull();
});

test("a refusal by the plan state is explained from its code and the list is read again", async () => {
  members("ACTIVE");
  answers.push({ status: 403, body: problem("PLAN_RESTRICTED", 403) });
  fireEvent.click(screen.getByRole("button", { name: "Reactivate Di Eze" }));
  await waitFor(() => expect(screen.getByRole("status").textContent).toBe(`${PLAN_RESTRICTED_MESSAGE} The list was refreshed.`));
  expect(refresh).toHaveBeenCalled();
});

test("a refusal by the member limit says so and reads the list again", async () => {
  members("ACTIVE");
  answers.push({ status: 409, body: problem("MEMBER_LIMIT_REACHED", 409) });
  fireEvent.click(screen.getByRole("button", { name: "Reactivate Di Eze" }));
  await waitFor(() => expect(screen.getByRole("status").textContent).toBe(`${MEMBER_LIMIT_MESSAGE} The list was refreshed.`));
  expect(refresh).toHaveBeenCalled();
});

// ---- invitations

test("while read-only, Invite someone is off and opens nothing", () => {
  render(<InviteButton companyId={COMPANY} callerRole="OWNER" companyStatus="ACTIVE" planState="EXPIRED_READ_ONLY" />);
  const button = screen.getByRole("button", { name: "Invite someone" });
  expect(button.getAttribute("aria-disabled")).toBe("true");
  expect(button.getAttribute("aria-describedby")).toBe(PLAN_STATUS_ID);
  fireEvent.click(button);
  expect(document.querySelector("dialog")).toBeNull();
  expect(sent()).toBe(0);
});

test("with a running plan, or an unknown state, Invite someone works as before", () => {
  for (const state of ["TRIALING", "ACTIVE", null] as const) {
    const { unmount } = render(<InviteButton companyId={COMPANY} callerRole="OWNER" companyStatus="ACTIVE" planState={state} />);
    expect(screen.getByRole("button", { name: "Invite someone" }).getAttribute("aria-disabled")).toBeNull();
    unmount();
  }
});

test("a person who may not invite still sees no button, whatever the plan", () => {
  const { container } = render(
    <InviteButton companyId={COMPANY} callerRole="AGENT" companyStatus="ACTIVE" planState="EXPIRED_READ_ONLY" />,
  );
  expect(container.textContent).toBe("");
});

test("an invitation refused by the plan state says so in the dialog and refreshes the page", async () => {
  answers.push({ status: 403, body: problem("PLAN_RESTRICTED", 403) });
  render(<InviteButton companyId={COMPANY} callerRole="OWNER" companyStatus="ACTIVE" planState="TRIALING" />);
  fireEvent.click(screen.getByRole("button", { name: "Invite someone" }));
  const dialog = await screen.findByRole("dialog");
  fireEvent.change(within(dialog).getByLabelText("Email address"), { target: { value: "new@example.com" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Send invitation" }));
  expect((await within(dialog).findByRole("alert")).textContent).toBe(PLAN_RESTRICTED_MESSAGE);
  expect(refresh).toHaveBeenCalled();
});

test("while read-only, resending is off and revoking still works", async () => {
  render(
    <>
      <p id={PLAN_STATUS_ID}>banner</p>
      <InvitationsList companyId={COMPANY} callerRole="OWNER" companyStatus="ACTIVE" invitations={[invite]} planState="EXPIRED_READ_ONLY" />
    </>,
  );
  const resend = screen.getByRole("button", { name: "Resend the invitation to ana2@example.com" });
  expect(resend.getAttribute("aria-disabled")).toBe("true");
  expect(resend.getAttribute("aria-describedby")).toBe(PLAN_STATUS_ID);
  fireEvent.click(resend);
  expect(sent()).toBe(0);
  const revoke = screen.getByRole("button", { name: "Revoke the invitation to ana2@example.com" });
  expect(revoke.getAttribute("aria-disabled")).toBeNull();
  fireEvent.click(revoke);
  expect(await screen.findByRole("dialog")).toBeTruthy();
});

test("once locked, revoking is off too", () => {
  render(
    <InvitationsList companyId={COMPANY} callerRole="OWNER" companyStatus="ACTIVE" invitations={[invite]} planState="EXPIRED_LOCKED" />,
  );
  const revoke = screen.getByRole("button", { name: "Revoke the invitation to ana2@example.com" });
  expect(revoke.getAttribute("aria-disabled")).toBe("true");
  fireEvent.click(revoke);
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(sent()).toBe(0);
});

// ---- ownership

const pendingTransfer: TransferView = {
  id: "t1",
  status: "PENDING",
  initiatorName: "Ana Silva",
  recipientName: "Cy Dube",
  youAreInitiator: true,
  youAreRecipient: false,
  expires: "Nov 2, 2026",
} as TransferView;

function panel(mode: "owner" | "recipient", state: SubscriptionState | null, transfer: TransferView | null, candidates = [{ id: "m1", name: "Cy Dube", role: "AGENT" }]) {
  return render(
    <>
      <p id={PLAN_STATUS_ID}>banner</p>
      <OwnershipPanel
        mode={mode}
        companyId={COMPANY}
        companyName="Acme"
        companyStatus="ACTIVE"
        transfer={transfer}
        candidates={candidates}
        planState={state}
      />
    </>,
  );
}

test("once the company is locked, starting, cancelling and accepting a transfer are off and send nothing", () => {
  const start = panel("owner", "EXPIRED_LOCKED", null);
  const startButton = screen.getByRole("button", { name: "Transfer ownership" });
  expect(startButton.getAttribute("aria-disabled")).toBe("true");
  expect(startButton.getAttribute("aria-describedby")).toBe(PLAN_STATUS_ID);
  fireEvent.change(screen.getByLabelText("New Owner"), { target: { value: "m1" } });
  fireEvent.click(startButton);
  start.unmount();

  const cancel = panel("owner", "EXPIRED_LOCKED", pendingTransfer);
  const cancelButton = screen.getByRole("button", { name: "Cancel the request" });
  expect(cancelButton.getAttribute("aria-disabled")).toBe("true");
  fireEvent.click(cancelButton);
  expect(screen.queryByRole("dialog")).toBeNull();
  cancel.unmount();

  panel("recipient", "EXPIRED_LOCKED", { ...pendingTransfer, youAreInitiator: false, youAreRecipient: true } as TransferView);
  const accept = screen.getByRole("button", { name: "Accept ownership" });
  expect(accept.getAttribute("aria-disabled")).toBe("true");
  fireEvent.click(accept);
  expect(sent()).toBe(0);
});

test("while read-only, with no plan, or with an unknown state, the transfer steps work as before", () => {
  for (const state of ["EXPIRED_READ_ONLY", "NO_PLAN", "TRIALING", "ACTIVE", null] as const) {
    const { unmount } = panel("owner", state, null);
    expect(screen.getByRole("button", { name: "Transfer ownership" }).getAttribute("aria-disabled"), String(state)).toBeNull();
    unmount();
  }
});
