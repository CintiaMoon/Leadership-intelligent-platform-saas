import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
}));

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { toPlanView, type SubscriptionState } from "@/lib/plan";

import { PlanBanner, PLAN_STATUS_ID } from "./PlanBanner";
import { PlanDetails } from "./PlanDetails";
import { PlanEnded } from "./PlanEnded";

afterEach(cleanup);

const COMPANY = "11111111-1111-4111-8111-111111111111";
const NOW = new Date("2026-11-03T12:00:00Z");

function plan(state: SubscriptionState, overrides: object = {}) {
  return toPlanView({
    plan: state === "NO_PLAN" ? null : { code: state === "ACTIVE" ? "TEAM" : "TRIAL", name: state === "ACTIVE" ? "Team" : "Trial", version: 1 },
    state,
    trialEndsAt: state === "ACTIVE" || state === "NO_PLAN" ? null : "2026-11-10T12:00:00Z",
    readOnlyEndsAt: state === "ACTIVE" || state === "NO_PLAN" ? null : "2026-11-24T12:00:00Z",
    features: ["CORE_SCORING", "BENCHMARKING"],
    limits: [
      { key: "TEAMS", value: 3, enforced: false, usage: null },
      { key: "ACTIVE_MEMBERS", value: 15, enforced: true, usage: 4 },
    ],
    ...overrides,
  });
}

// ---- the banner

test("while the trial runs the banner counts the days left", () => {
  render(<PlanBanner plan={plan("TRIALING")} now={NOW} />);
  const banner = screen.getByText("7 days left in your trial.");
  expect(banner.id).toBe(PLAN_STATUS_ID);
  // Not a live region: the pages keep those for the result of an action.
  expect(screen.queryByRole("status")).toBeNull();
});

test("read-only, no plan and locked each get a calm notice, never an alert or a live region", () => {
  for (const [state, text] of [
    ["EXPIRED_READ_ONLY", /trial has ended/],
    ["NO_PLAN", /no plan yet/],
    ["EXPIRED_LOCKED", /company is locked/],
  ] as const) {
    const { unmount } = render(<PlanBanner plan={plan(state)} now={NOW} />);
    expect(document.getElementById(PLAN_STATUS_ID)?.textContent).toMatch(text);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
    unmount();
  }
});

test("an active plan, or a plan that cannot be read, shows no banner", () => {
  const { container, rerender } = render(<PlanBanner plan={plan("ACTIVE")} now={NOW} />);
  expect(container.textContent).toBe("");
  rerender(<PlanBanner plan={null} now={NOW} />);
  expect(container.textContent).toBe("");
});

// ---- the Plan page

test("the plan page shows the plan, its state, the trial end, the limits with usage and what is included", () => {
  render(<PlanDetails plan={plan("TRIALING")} now={NOW} />);
  expect(screen.getByText("Trial (version 1)")).toBeTruthy();
  expect(screen.getByText("Nov 10, 2026")).toBeTruthy();
  expect(screen.getByText("4 of 15 used")).toBeTruthy();
  expect(screen.getByText("Up to 3")).toBeTruthy();
  const included = screen.getByRole("heading", { name: "Included" }).closest("section")!;
  expect(within(included).getByText("Core scoring")).toBeTruthy();
  expect(within(included).getByText("Benchmarking")).toBeTruthy();
});

test("the plan page has no button and no link that changes the plan", () => {
  render(<PlanDetails plan={plan("EXPIRED_READ_ONLY")} now={NOW} />);
  expect(screen.queryByRole("button")).toBeNull();
  expect(screen.queryByRole("link")).toBeNull();
  expect(screen.getByText(/Plan changes are made by our team/)).toBeTruthy();
});

test("usage above the limit is shown plainly, and nobody is said to be removed", () => {
  render(
    <PlanDetails
      plan={plan("ACTIVE", { limits: [{ key: "ACTIVE_MEMBERS", value: 15, enforced: true, usage: 20 }] })}
      now={NOW}
    />,
  );
  expect(screen.getByText("20 of 15 used")).toBeTruthy();
  expect(screen.getByText(/more active members than this plan allows/)).toBeTruthy();
  expect(screen.getByText(/Nobody was removed or suspended/)).toBeTruthy();
});

test("a company with no plan says none, and shows no dates", () => {
  render(<PlanDetails plan={plan("NO_PLAN", { features: [], limits: [] })} now={NOW} />);
  expect(screen.getByText("None")).toBeTruthy();
  expect(screen.queryByText(/Trial ends/)).toBeNull();
  expect(screen.queryByRole("heading", { name: "Limits" })).toBeNull();
});

test("an active plan shows no read-only date", () => {
  render(<PlanDetails plan={plan("ACTIVE", { readOnlyEndsAt: "2026-11-24T12:00:00Z" })} now={NOW} />);
  expect(screen.queryByText(/Read-only until/)).toBeNull();
});

// ---- the locked page

test("the page for someone who is not the Owner of a locked company explains it and points back", () => {
  render(<PlanEnded />);
  expect(screen.getByRole("heading", { name: "This company is locked." })).toBeTruthy();
  expect(screen.getByText(/only its Owner can open it/)).toBeTruthy();
  expect(screen.getByRole("link", { name: "Go to my companies" }).getAttribute("href")).toBe("/companies");
});

// ---- the tab

test("Owners and Admins have a Plan tab; Agents and Viewers have none", () => {
  const { unmount } = render(<CompanyHeader companyId={COMPANY} name="Acme" role="ADMIN" active="plan" />);
  const tab = screen.getByRole("link", { name: "Plan" });
  expect(tab.getAttribute("href")).toBe(`/companies/${COMPANY}/plan`);
  expect(tab.getAttribute("aria-current")).toBe("page");
  unmount();
  render(<CompanyHeader companyId={COMPANY} name="Acme" role="AGENT" />);
  expect(screen.queryByRole("link", { name: "Plan" })).toBeNull();
});
