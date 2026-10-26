import { afterEach, beforeEach, expect, test, vi } from "vitest";

const redirect = vi.fn((target: string) => {
  throw new Error(`redirect:${target}`);
});
vi.mock("next/navigation", () => ({ redirect: (target: string) => redirect(target) }));
vi.mock("next/headers", () => ({ cookies: async () => new Map(), headers: async () => new Headers() }));

import type { BffConfig } from "./bff";
import { loadTransfer, readTransfer } from "./ownership";

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
  id: "t1",
  status: "PENDING",
  initiatorName: "Ana",
  recipientName: "Bo",
  youAreInitiator: false,
  youAreRecipient: true,
  createdAt: "2026-10-26T10:00:00Z",
  expiresAt: "2026-11-02T10:00:00Z",
};

test("a transfer that is there is found, with only the fields the page needs", async () => {
  fetchMock.mockResolvedValue(Response.json(dto));
  const result = await readTransfer(incoming, config, COMPANY);
  expect(result.kind).toBe("found");
  expect(fetchMock.mock.calls[0][0]).toBe(`http://api.test/api/v1/companies/${COMPANY}/ownership-transfer`);
  expect(result.kind === "found" && "createdAt" in result.transfer).toBe(false);
});

test("a 404 means nothing is pending for this person, any other failure is an error and not the same thing", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 404 }));
  expect((await readTransfer(incoming, config, COMPANY)).kind).toBe("none");
  for (const status of [500, 502, 503]) {
    fetchMock.mockResolvedValue(new Response(null, { status }));
    expect((await readTransfer(incoming, config, COMPANY)).kind, String(status)).toBe("error");
  }
  fetchMock.mockRejectedValue(new Error("network"));
  expect((await readTransfer(incoming, config, COMPANY)).kind).toBe("error");
});

test("an ended session goes to sign in", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
  await expect(readTransfer(incoming, config, COMPANY)).rejects.toThrow("redirect:/login?notice=expired");
});

test("the banner version treats a failed read like nothing pending", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 503 }));
  expect(await loadTransfer(incoming, config, COMPANY)).toBeNull();
  fetchMock.mockResolvedValue(Response.json(dto));
  expect((await loadTransfer(incoming, config, COMPANY))?.id).toBe("t1");
});
