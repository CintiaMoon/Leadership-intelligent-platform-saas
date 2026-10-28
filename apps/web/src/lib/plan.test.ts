import { expect, test } from "vitest";

import {
  atMemberLimit,
  bannerFor,
  daysLeft,
  featureLabel,
  gate,
  isSubscriptionState,
  MEMBER_LIMIT_REASON,
  memberLimit,
  reactivateGate,
  toPlanView,
  type PlanAction,
  type PlanView,
  type SubscriptionState,
} from "./plan";

const STATES: SubscriptionState[] = ["NO_PLAN", "TRIALING", "EXPIRED_READ_ONLY", "EXPIRED_LOCKED", "ACTIVE"];
const ACTIONS: PlanAction[] = ["invite", "resend", "revoke", "changeRole", "reactivate", "suspend", "remove", "transfer"];

/** The spec's gating table (section 3a) for the controls this screen has: true means the state lets the control work. */
const TABLE: Record<PlanAction, Record<SubscriptionState, boolean>> = {
  invite: { NO_PLAN: false, TRIALING: true, EXPIRED_READ_ONLY: false, EXPIRED_LOCKED: false, ACTIVE: true },
  resend: { NO_PLAN: false, TRIALING: true, EXPIRED_READ_ONLY: false, EXPIRED_LOCKED: false, ACTIVE: true },
  revoke: { NO_PLAN: true, TRIALING: true, EXPIRED_READ_ONLY: true, EXPIRED_LOCKED: false, ACTIVE: true },
  changeRole: { NO_PLAN: false, TRIALING: true, EXPIRED_READ_ONLY: false, EXPIRED_LOCKED: false, ACTIVE: true },
  reactivate: { NO_PLAN: false, TRIALING: true, EXPIRED_READ_ONLY: false, EXPIRED_LOCKED: false, ACTIVE: true },
  suspend: { NO_PLAN: true, TRIALING: true, EXPIRED_READ_ONLY: true, EXPIRED_LOCKED: false, ACTIVE: true },
  remove: { NO_PLAN: true, TRIALING: true, EXPIRED_READ_ONLY: true, EXPIRED_LOCKED: false, ACTIVE: true },
  // Start, cancel and accept an ownership transfer: allowed until the company is locked.
  transfer: { NO_PLAN: true, TRIALING: true, EXPIRED_READ_ONLY: true, EXPIRED_LOCKED: false, ACTIVE: true },
};

test("every control in every state follows the spec table, and a refusal always says why", () => {
  for (const action of ACTIONS) {
    for (const state of STATES) {
      const result = gate(action, state);
      expect(result.allowed, `${action} in ${state}`).toBe(TABLE[action][state]);
      if (!result.allowed) {
        expect(result.reason.length).toBeGreaterThan(20);
      }
    }
  }
});

test("when the state is not known to the screen the API decides, so no control is turned off", () => {
  for (const action of ACTIONS) {
    expect(gate(action, null).allowed).toBe(true);
  }
});

test("the reasons name the situation", () => {
  expect(gate("invite", "EXPIRED_READ_ONLY")).toMatchObject({ reason: expect.stringContaining("read-only") });
  expect(gate("invite", "NO_PLAN")).toMatchObject({ reason: expect.stringContaining("no plan") });
  expect(gate("invite", "EXPIRED_LOCKED")).toMatchObject({ reason: expect.stringContaining("plan has ended") });
});

test("a reactivation is held back by the member limit only when the state lets it through", () => {
  expect(reactivateGate("TRIALING", false).allowed).toBe(true);
  expect(reactivateGate("TRIALING", true)).toEqual({ allowed: false, reason: MEMBER_LIMIT_REASON });
  expect(reactivateGate("ACTIVE", true)).toEqual({ allowed: false, reason: MEMBER_LIMIT_REASON });
  expect(reactivateGate(null, true)).toEqual({ allowed: false, reason: MEMBER_LIMIT_REASON });
  // The state's own reason wins over the limit's.
  const readOnly = reactivateGate("EXPIRED_READ_ONLY", true);
  expect(readOnly.allowed).toBe(false);
  expect(readOnly).not.toEqual({ allowed: false, reason: MEMBER_LIMIT_REASON });
});

const dto = {
  plan: { code: "TRIAL", name: "Trial", version: 1 },
  state: "TRIALING",
  trialEndsAt: "2026-11-10T12:00:00Z",
  readOnlyEndsAt: "2026-11-24T12:00:00Z",
  features: ["CORE_SCORING", "CSV_EXPORT"],
  limits: [
    { key: "TEAMS", value: 3, enforced: false, usage: null },
    { key: "ACTIVE_MEMBERS", value: 15, enforced: true, usage: 15 },
  ],
  secret: "not copied",
};

test("the plan view copies only the fields it needs and formats dates the same on server and browser", () => {
  const view = toPlanView(dto);
  expect(view).toEqual({
    state: "TRIALING",
    plan: { code: "TRIAL", name: "Trial", version: 1 },
    trialEnds: "Nov 10, 2026",
    readOnlyEnds: "Nov 24, 2026",
    trialEndsAt: "2026-11-10T12:00:00Z",
    features: ["CORE_SCORING", "CSV_EXPORT"],
    limits: [
      { key: "TEAMS", label: "Teams", value: 3, enforced: false, usage: null },
      { key: "ACTIVE_MEMBERS", label: "Active members", value: 15, enforced: true, usage: 15 },
    ],
  });
  expect(JSON.stringify(view)).not.toContain("not copied");
});

test("a company with no plan has no plan, no dates and an unknown state reads as no plan", () => {
  const view = toPlanView({ plan: null, state: "NO_PLAN", trialEndsAt: null, readOnlyEndsAt: null, features: [], limits: [] });
  expect(view).toMatchObject({ plan: null, trialEnds: null, readOnlyEnds: null, state: "NO_PLAN" });
  expect(toPlanView({ ...dto, state: "SOMETHING_NEW" }).state).toBe("NO_PLAN");
  expect(isSubscriptionState("ACTIVE")).toBe(true);
  expect(isSubscriptionState("PAST_DUE")).toBe(false);
  expect(isSubscriptionState(undefined)).toBe(false);
});

test("only the enforced member limit counts, and at or above it a reactivation is off", () => {
  const at = toPlanView(dto);
  expect(memberLimit(at)).toEqual({ value: 15, usage: 15 });
  expect(atMemberLimit(at)).toBe(true);
  const below = toPlanView({ ...dto, limits: [{ key: "ACTIVE_MEMBERS", value: 15, enforced: true, usage: 14 }] });
  expect(atMemberLimit(below)).toBe(false);
  const over = toPlanView({ ...dto, limits: [{ key: "ACTIVE_MEMBERS", value: 15, enforced: true, usage: 20 }] });
  expect(atMemberLimit(over)).toBe(true);
  const notEnforced = toPlanView({ ...dto, limits: [{ key: "ACTIVE_MEMBERS", value: 15, enforced: false, usage: null }] });
  expect(memberLimit(notEnforced)).toBeNull();
  expect(atMemberLimit(notEnforced)).toBe(false);
  expect(atMemberLimit(null)).toBe(false);
});

test("days left round up and never go below zero", () => {
  const ends = "2026-11-10T12:00:00Z";
  expect(daysLeft(ends, new Date("2026-11-03T12:00:00Z"))).toBe(7);
  expect(daysLeft(ends, new Date("2026-11-03T12:00:01Z"))).toBe(7);
  expect(daysLeft(ends, new Date("2026-11-03T11:59:59Z"))).toBe(8);
  expect(daysLeft(ends, new Date("2026-11-10T11:59:59Z"))).toBe(1);
  expect(daysLeft(ends, new Date("2026-11-10T12:00:00Z"))).toBe(0);
  expect(daysLeft(ends, new Date("2026-12-01T00:00:00Z"))).toBe(0);
});

function view(state: SubscriptionState): PlanView {
  return toPlanView({ ...dto, state });
}

test("each state has its own banner, and an active plan has none", () => {
  const now = new Date("2026-11-03T12:00:00Z");
  expect(bannerFor(view("TRIALING"), now)).toEqual({ tone: "info", text: "7 days left in your trial." });
  expect(bannerFor(view("TRIALING"), new Date("2026-11-10T00:00:00Z"))?.text).toBe("Your trial ends within a day.");
  const readOnly = bannerFor(view("EXPIRED_READ_ONLY"), now);
  expect(readOnly?.tone).toBe("notice");
  expect(readOnly?.text).toContain("Nov 24, 2026");
  expect(bannerFor(view("EXPIRED_LOCKED"), now)?.text).toContain("locked");
  expect(bannerFor(view("NO_PLAN"), now)?.text).toContain("no plan");
  expect(bannerFor(view("ACTIVE"), now)).toBeNull();
});

test("feature keys have readable names and an unknown one is shown as is", () => {
  expect(featureLabel("CSV_EXPORT")).toBe("CSV export");
  expect(featureLabel("NEW_THING")).toBe("NEW_THING");
});
