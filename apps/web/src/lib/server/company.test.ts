import { afterEach, beforeEach, expect, test, vi } from "vitest";

import type { BffConfig } from "./bff";

const config: BffConfig = {
  apiBaseUrl: "http://api.test",
  credential: "credential-for-tests",
  publicOrigin: "https://app.example.com",
  csrfCookie: "__Host-csrf",
  sessionCookie: "__Host-session",
  deviceCookie: "__Host-device",
  clientIpSource: "rightmost",
};
const incoming = new Headers({ cookie: "__Host-session=abc" });

vi.mock("./session", async () => {
  const bff = await vi.importActual<typeof import("./bff")>("./bff");
  return {
    COMPANY_ID: bff.UUID,
    requireSession: async () => ({ session: {}, incoming, config }),
    signInAgain: () => {
      throw new Error("redirect:/login");
    },
  };
});

import { loadCompany } from "./company";

const COMPANY = "11111111-1111-4111-8111-111111111111";
const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const problem = (status: number, extra: object = {}) =>
  Response.json({ type: "about:blank", title: "x", status, ...extra }, { status });

test("a company the person can use is loaded", async () => {
  fetchMock.mockResolvedValue(
    Response.json({ id: COMPANY, name: "Acme", status: "ACTIVE", role: "OWNER", createdAt: "2026-10-01T00:00:00Z" }),
  );
  const result = await loadCompany(COMPANY);
  expect(result.kind).toBe("ok");
});

test("a member of a company whose plan has ended is told so, with the state", async () => {
  fetchMock.mockResolvedValue(problem(403, { code: "PLAN_RESTRICTED", subscriptionState: "EXPIRED_LOCKED" }));
  expect(await loadCompany(COMPANY)).toEqual({ kind: "restricted", state: "EXPIRED_LOCKED" });
});

test("a company that is not theirs, or not there, looks the same as before", async () => {
  fetchMock.mockResolvedValueOnce(problem(404, { code: "NOT_FOUND" }));
  expect(await loadCompany(COMPANY)).toEqual({ kind: "unavailable" });
  fetchMock.mockResolvedValueOnce(problem(403, { code: "FORBIDDEN" }));
  expect(await loadCompany(COMPANY)).toEqual({ kind: "unavailable" });
});

test("an id that is not a UUID never reaches the API", async () => {
  expect(await loadCompany("../../etc")).toEqual({ kind: "unavailable" });
  expect(fetchMock).not.toHaveBeenCalled();
});

test("an unreachable or failing API is an error", async () => {
  fetchMock.mockResolvedValueOnce(problem(503));
  expect(await loadCompany(COMPANY)).toEqual({ kind: "error" });
  fetchMock.mockRejectedValueOnce(new Error("down"));
  expect(await loadCompany(COMPANY)).toEqual({ kind: "error" });
});
