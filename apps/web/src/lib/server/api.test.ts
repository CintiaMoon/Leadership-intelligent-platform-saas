import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { readApi } from "./api";
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

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

test("a read sends the session cookie and the BFF credential and returns the body", async () => {
  fetchMock.mockResolvedValue(Response.json([{ id: "c1" }]));
  const incoming = new Headers({ cookie: "__Host-session=abc; analytics=1", authorization: "Bearer x" });

  const result = await readApi<{ id: string }[]>(incoming, "/api/v1/companies", config);

  expect(result).toEqual({ ok: true, data: [{ id: "c1" }] });
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe("http://api.test/api/v1/companies");
  const headers = init.headers as Headers;
  expect(headers.get("cookie")).toBe("__Host-session=abc");
  expect(headers.get("x-bff-credential")).toBe("credential-for-tests");
  expect(headers.has("authorization")).toBe(false);
  expect(init.cache).toBe("no-store");
});

test("a failure keeps only its status, never the server's text", async () => {
  fetchMock.mockResolvedValue(Response.json({ detail: "secret detail" }, { status: 403 }));
  expect(await readApi(new Headers(), "/api/v1/companies/x/members", config)).toEqual({ ok: false, status: 403 });
});

test("an unreachable API is status 0", async () => {
  fetchMock.mockRejectedValue(new Error("down"));
  expect(await readApi(new Headers(), "/api/v1/companies", config)).toEqual({ ok: false, status: 0 });
});

test("only plain /api/v1/ paths are read", async () => {
  for (const path of [
    "/api/auth/session",
    "/api/v1/../admin",
    "/api/v1/companies?x=1",
    "http://evil.example/api/v1/a",
    "/api/v1/companies/a%2Fb",
    "/api/v1/companies/%2e%2e/admin",
    "/api/v1//companies",
    "/api/v1/companies/",
    "/api/v1/companies/a.b",
    "/api/v1/companies#x",
    "/api/v1/",
  ]) {
    await expect(readApi(new Headers(), path, config)).rejects.toThrow();
  }
  expect(fetchMock).not.toHaveBeenCalled();
});
