import { afterEach, beforeEach, expect, test, vi } from "vitest";

const redirect = vi.fn((target: string) => {
  throw new Error(`redirect:${target}`);
});
vi.mock("next/navigation", () => ({ redirect: (target: string) => redirect(target) }));
vi.mock("next/headers", () => ({ cookies: async () => new Map(), headers: async () => new Headers() }));

import type { BffConfig } from "./bff";
import { loadPlan, readPlan } from "./subscription";

const config: BffConfig = {
  apiBaseUrl: "http://api.test",
  credential: "credential-for-tests",
  publicOrigin: "https://app.example.com",
  csrfCookie: "__Host-csrf",
  sessionCookie: "__Host-session",
  deviceCookie: "__Host-device",
  clientIpSource: "rightmost",
};
const COMPANY = "11111111-1111-4111-8111-111111111111";
const fetchMock = vi.fn();
const incoming = new Headers({ cookie: "__Host-session=abc" });

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  redirect.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const dto = {
  plan: { code: "STARTER", name: "Starter", version: 1 },
  state: "ACTIVE",
  trialEndsAt: null,
  readOnlyEndsAt: null,
  features: ["CORE_SCORING"],
  limits: [{ key: "ACTIVE_MEMBERS", value: 15, enforced: true, usage: 3 }],
};

const problem = (status: number, extra: object = {}) =>
  Response.json({ type: "about:blank", title: "x", status, ...extra }, { status });

test("the plan is read from the subscription route of the company in the address", async () => {
  fetchMock.mockResolvedValue(Response.json(dto));
  const result = await readPlan(incoming, config, COMPANY);
  expect(result.kind).toBe("found");
  expect(fetchMock.mock.calls[0][0]).toBe(`http://api.test/api/v1/companies/${COMPANY}/subscription`);
  expect(result.kind === "found" && result.plan.plan?.name).toBe("Starter");
});

test("a locked company says so to a member, with the state the API named", async () => {
  fetchMock.mockResolvedValue(problem(403, { code: "PLAN_RESTRICTED", subscriptionState: "EXPIRED_LOCKED" }));
  expect(await readPlan(incoming, config, COMPANY)).toEqual({ kind: "restricted", state: "EXPIRED_LOCKED" });
});

test("a plain 403 or 404 is just not available, and so is a restriction naming a state the screen does not know", async () => {
  fetchMock.mockResolvedValueOnce(problem(403, { code: "FORBIDDEN" }));
  expect(await readPlan(incoming, config, COMPANY)).toEqual({ kind: "unavailable" });
  fetchMock.mockResolvedValueOnce(problem(404, { code: "NOT_FOUND" }));
  expect(await readPlan(incoming, config, COMPANY)).toEqual({ kind: "unavailable" });
  fetchMock.mockResolvedValueOnce(problem(403, { code: "PLAN_RESTRICTED", subscriptionState: "PAST_DUE" }));
  expect(await readPlan(incoming, config, COMPANY)).toEqual({ kind: "unavailable" });
});

test("a failed read is an error, never 'no plan'", async () => {
  fetchMock.mockResolvedValueOnce(problem(500));
  expect(await readPlan(incoming, config, COMPANY)).toEqual({ kind: "error" });
  fetchMock.mockRejectedValueOnce(new Error("down"));
  expect(await readPlan(incoming, config, COMPANY)).toEqual({ kind: "error" });
});

test("an ended session goes to sign in", async () => {
  fetchMock.mockResolvedValue(problem(401));
  await expect(readPlan(incoming, config, COMPANY)).rejects.toThrow(/redirect:/);
});

test("the banner pages get the plan when it can be read and nothing otherwise", async () => {
  fetchMock.mockResolvedValueOnce(Response.json(dto));
  expect((await loadPlan(incoming, config, COMPANY))?.state).toBe("ACTIVE");
  fetchMock.mockResolvedValueOnce(problem(403, { code: "FORBIDDEN" }));
  expect(await loadPlan(incoming, config, COMPANY)).toBeNull();
  fetchMock.mockResolvedValueOnce(problem(500));
  expect(await loadPlan(incoming, config, COMPANY)).toBeNull();
});
