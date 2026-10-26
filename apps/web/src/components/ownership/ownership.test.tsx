import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

const push = vi.fn();
const replace = vi.fn();
const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace, refresh }),
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import type { Candidate, TransferView } from "@/lib/ownership";

import { OwnershipPanel } from "./OwnershipPanel";
import { TransferBanner } from "./TransferBanner";

const fetchMock = vi.fn();
const COMPANY = "11111111-1111-4111-8111-111111111111";
const TRANSFER = "22222222-2222-4222-8222-222222222222";
const base = `/api/companies/${COMPANY}/ownership-transfers`;

const candidates: Candidate[] = [
  { id: "b2", name: "Bo Chen", role: "ADMIN" },
  { id: "c3", name: "Cy Dube", role: "AGENT" },
];

const pending: TransferView = {
  id: TRANSFER,
  status: "PENDING",
  initiatorName: "Ana Silva",
  recipientName: "Bo Chen",
  youAreInitiator: true,
  youAreRecipient: false,
  expires: "Nov 2, 2026, 10:00 AM UTC",
};
const incoming: TransferView = { ...pending, youAreInitiator: false, youAreRecipient: true };

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

/** The requests that were not CSRF lookups or the password check, as "METHOD url". */
function calls(): string[] {
  return fetchMock.mock.calls
    .filter(([url]) => !String(url).includes("/csrf") && !String(url).includes("/step-up"))
    .map(([url, init]) => `${init?.method ?? "GET"} ${url}`);
}

function renderPanel(props: Partial<React.ComponentProps<typeof OwnershipPanel>> = {}) {
  return render(
    <OwnershipPanel
      mode="owner"
      companyId={COMPANY}
      companyName="Acme"
      companyStatus="ACTIVE"
      transfer={null}
      candidates={candidates}
      {...props}
    />,
  );
}

function choose(id: string) {
  fireEvent.change(screen.getByRole("combobox", { name: "New Owner" }), { target: { value: id } });
  fireEvent.click(screen.getByRole("button", { name: "Transfer ownership" }));
}

// ---- the Owner

test("the Owner is warned, can pick an active member, and nothing is sent before the button is pressed", () => {
  renderPanel();
  expect(screen.getByText(/You will become an Admin\. The new Owner can remove you\./)).toBeTruthy();
  const select = screen.getByRole("combobox", { name: "New Owner" }) as HTMLSelectElement;
  expect([...select.options].map((o) => o.text)).toEqual(["Choose a member", "Bo Chen (Admin)", "Cy Dube (Agent)"]);
  expect(calls()).toEqual([]);
});

test("pressing the button without a choice explains and sends nothing", () => {
  renderPanel();
  fireEvent.click(screen.getByRole("button", { name: "Transfer ownership" }));
  expect(screen.getByRole("alert").textContent).toBe("Choose the member who should become the Owner.");
  expect(calls()).toEqual([]);
});

test("asking a member sends the membership id once and says so", async () => {
  answer(`POST ${base}`, { status: 201, body: { id: TRANSFER } });
  renderPanel();
  choose("b2");

  await waitFor(() =>
    expect(screen.getByRole("status").textContent).toBe("Bo Chen was asked to become the Owner. They have 7 days to accept."),
  );
  expect(calls()).toEqual([`POST ${base}`]);
  const sent = fetchMock.mock.calls.find(([url, init]) => url === base && init?.method === "POST");
  expect(JSON.parse(sent![1].body)).toEqual({ recipientMembershipId: "b2" });
  expect(refresh).toHaveBeenCalled();
});

test("an old login asks for the password and sends the same request once more", async () => {
  answer(
    `POST ${base}`,
    { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) },
    { status: 201, body: { id: TRANSFER } },
  );
  answer("POST /api/auth/step-up", { status: 204 });
  renderPanel();
  choose("c3");

  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "correct horse battery staple" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/Cy Dube was asked/));
  expect(calls()).toEqual([`POST ${base}`, `POST ${base}`]);
});

test("a refusal says the transfer changed; a limit and a busy service say to wait", async () => {
  answer(`POST ${base}`, { status: 409, body: problem("CONFLICT", 409) });
  renderPanel();
  choose("b2");
  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("This transfer changed. The page was refreshed."));
  expect(refresh).toHaveBeenCalled();
  cleanup();

  answer(`POST ${base}`, { status: 429, body: problem("RATE_LIMITED", 429), headers: { "Retry-After": "7200" } });
  renderPanel();
  choose("b2");
  await waitFor(() =>
    expect(screen.getByRole("status").textContent).toMatch(/Too many attempts\. Please try again in about 120 minutes\./),
  );
  cleanup();

  answer(`POST ${base}`, { status: 503, body: problem("SERVICE_UNAVAILABLE", 503) });
  renderPanel();
  choose("b2");
  await waitFor(() => expect(screen.getByRole("status").textContent).toMatch(/busy right now/));
});

test("an ended session goes to sign in", async () => {
  answer(`POST ${base}`, { status: 401, body: problem("UNAUTHENTICATED", 401) });
  renderPanel();
  choose("b2");
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?notice=expired"));
});

test("with nobody else to ask, the form is replaced by a hint", () => {
  renderPanel({ candidates: [] });
  expect(screen.queryByRole("combobox")).toBeNull();
  expect(screen.getByText(/no other active member to ask/)).toBeTruthy();
});

test("a company that is being deleted cannot be transferred", () => {
  renderPanel({ companyStatus: "PENDING_DELETION" });
  expect(screen.queryByRole("combobox")).toBeNull();
  expect(screen.getByText(/scheduled for deletion/)).toBeTruthy();
});

test("a pending request shows who and until when, and cancelling names the person first", async () => {
  answer(`POST ${base}/${TRANSFER}/cancel`, { status: 200, body: {} });
  renderPanel({ transfer: pending, candidates: [] });
  expect(screen.getByRole("heading", { name: "Waiting for Bo Chen" })).toBeTruthy();
  expect(screen.getByText(/expires Nov 2, 2026, 10:00 AM UTC/)).toBeTruthy();
  expect(screen.queryByRole("combobox")).toBeNull();

  fireEvent.click(screen.getByRole("button", { name: "Cancel the request" }));
  const dialog = await screen.findByRole("dialog", { name: "Cancel the request to Bo Chen?" });
  expect(calls()).toEqual([]);
  fireEvent.click(within(dialog).getByRole("button", { name: "Cancel the request" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("The request to Bo Chen was cancelled."));
  expect(calls()).toEqual([`POST ${base}/${TRANSFER}/cancel`]);
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
});

test("closing the cancel confirmation sends nothing", async () => {
  renderPanel({ transfer: pending, candidates: [] });
  fireEvent.click(screen.getByRole("button", { name: "Cancel the request" }));
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(calls()).toEqual([]);
});

test("an expired request says so and offers a new one", () => {
  renderPanel({ transfer: { ...pending, status: "EXPIRED" } });
  expect(screen.getByText(/Expired\. Bo Chen did not accept in time\./)).toBeTruthy();
  expect(screen.getByRole("combobox", { name: "New Owner" })).toBeTruthy();
});

test("names are text, never markup", () => {
  renderPanel({ candidates: [{ id: "x", name: "<img src=x onerror=alert(1)>", role: "ADMIN" }] });
  expect(document.querySelector("img")).toBeNull();
});

// ---- the recipient

test("the recipient sees who asked, what happens, and accepts with one request", async () => {
  answer(`POST ${base}/${TRANSFER}/accept`, { status: 200, body: {} });
  renderPanel({ mode: "recipient", transfer: incoming, candidates: [] });
  expect(screen.getByRole("heading", { name: "Ana Silva asked you to become the Owner" })).toBeTruthy();
  expect(screen.getByText(/you become the Owner of Acme and Ana Silva becomes an Admin/)).toBeTruthy();
  expect(screen.queryByRole("button", { name: /decline|reject/i })).toBeNull();

  fireEvent.click(screen.getByRole("button", { name: "Accept ownership" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("You are now the Owner of Acme."));
  expect(calls()).toEqual([`POST ${base}/${TRANSFER}/accept`]);
  expect(refresh).toHaveBeenCalled();
});

test("accepting with an old login asks for the password and sends it once more", async () => {
  answer(
    `POST ${base}/${TRANSFER}/accept`,
    { status: 403, body: problem("REAUTHENTICATION_REQUIRED", 403) },
    { status: 200, body: {} },
  );
  answer("POST /api/auth/step-up", { status: 204 });
  renderPanel({ mode: "recipient", transfer: incoming, candidates: [] });
  fireEvent.click(screen.getByRole("button", { name: "Accept ownership" }));

  const dialog = await screen.findByRole("dialog", { name: "Confirm your password" });
  fireEvent.change(within(dialog).getByLabelText("Password"), { target: { value: "correct horse battery staple" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Continue" }));

  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("You are now the Owner of Acme."));
  expect(calls()).toEqual([`POST ${base}/${TRANSFER}/accept`, `POST ${base}/${TRANSFER}/accept`]);
});

test("accepting a request that was cancelled meanwhile says it changed and reads the page again", async () => {
  answer(`POST ${base}/${TRANSFER}/accept`, { status: 409, body: problem("CONFLICT", 409) });
  renderPanel({ mode: "recipient", transfer: incoming, candidates: [] });
  fireEvent.click(screen.getByRole("button", { name: "Accept ownership" }));
  await waitFor(() => expect(screen.getByRole("status").textContent).toBe("This transfer changed. The page was refreshed."));
  expect(refresh).toHaveBeenCalled();
});

test("an expired request is one calm message with no button", () => {
  renderPanel({ mode: "recipient", transfer: { ...incoming, status: "EXPIRED" }, candidates: [] });
  expect(screen.getByRole("heading", { name: "This request has expired" })).toBeTruthy();
  expect(screen.queryByRole("button")).toBeNull();
});

test("someone with nothing waiting sees a plain page, whatever their role", () => {
  renderPanel({ mode: "none", candidates: [] });
  expect(screen.getByRole("heading", { name: "Nothing is waiting for you" })).toBeTruthy();
  expect(screen.queryByRole("button")).toBeNull();
  expect(screen.queryByRole("combobox")).toBeNull();
});

// ---- banner and tab

test("the banner names who asked and links to the Ownership page", () => {
  render(<TransferBanner companyId={COMPANY} initiatorName="Ana Silva" />);
  expect(screen.getByText("Ana Silva asked you to become the Owner of this company.")).toBeTruthy();
  expect(screen.getByRole("link", { name: "Review the request" }).getAttribute("href")).toBe(`/companies/${COMPANY}/ownership`);
});

test("the Ownership tab is for the Owner, and for anyone with a request waiting", () => {
  render(<CompanyHeader companyId={COMPANY} name="Acme" role="OWNER" active="ownership" />);
  expect(screen.getByRole("link", { name: "Ownership" }).getAttribute("aria-current")).toBe("page");
  expect(screen.getByRole("link", { name: "Members" })).toBeTruthy();
  cleanup();

  render(<CompanyHeader companyId={COMPANY} name="Acme" role="ADMIN" active="members" />);
  expect(screen.queryByRole("link", { name: "Ownership" })).toBeNull();
  cleanup();

  render(<CompanyHeader companyId={COMPANY} name="Acme" role="ADMIN" active="members" requestWaiting />);
  expect(screen.getByRole("link", { name: "Ownership" })).toBeTruthy();
  expect(screen.getByRole("link", { name: "Members" })).toBeTruthy();
  cleanup();

  render(<CompanyHeader companyId={COMPANY} name="Acme" role="VIEWER" requestWaiting />);
  expect(screen.getByRole("link", { name: "Ownership" })).toBeTruthy();
  expect(screen.queryByRole("link", { name: "Members" })).toBeNull();
  cleanup();

  render(<CompanyHeader companyId={COMPANY} name="Acme" role="VIEWER" />);
  expect(screen.queryByRole("navigation", { name: "Company" })).toBeNull();
});
