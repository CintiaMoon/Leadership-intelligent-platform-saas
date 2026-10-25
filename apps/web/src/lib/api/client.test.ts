import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { post, send } from "./client";

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function answer(status: number, body?: object, headers?: Record<string, string>) {
  fetchMock.mockImplementation(async (url: string) =>
    String(url).includes("/csrf")
      ? Response.json({ headerName: "X-CSRF-TOKEN", token: "csrf-token" })
      : new Response(body ? JSON.stringify(body) : null, { status, headers }),
  );
}

const ID = "11111111-1111-4111-8111-111111111111";

test("PATCH sends the session-bound CSRF token and a JSON body, and returns the answer", async () => {
  answer(200, { id: ID, role: "AGENT" });

  const result = await send("PATCH", `/api/companies/${ID}/members/${ID}`, { role: "AGENT" });

  expect(result).toEqual({ ok: true, status: 200, data: { id: ID, role: "AGENT" } });
  expect(fetchMock.mock.calls[0][0]).toBe("/api/auth/csrf");
  const [url, init] = fetchMock.mock.calls[1];
  expect(url).toBe(`/api/companies/${ID}/members/${ID}`);
  expect(init.method).toBe("PATCH");
  expect(init.headers).toEqual({ "X-CSRF-TOKEN": "csrf-token", "Content-Type": "application/json" });
  expect(JSON.parse(init.body)).toEqual({ role: "AGENT" });
  expect(init.credentials).toBe("same-origin");
});

test("DELETE has no body and no content type", async () => {
  answer(204);

  const result = await send("DELETE", `/api/companies/${ID}/invitations/${ID}`);

  expect(result).toEqual({ ok: true, status: 204, data: null });
  const [, init] = fetchMock.mock.calls[1];
  expect(init.method).toBe("DELETE");
  expect(init.body).toBeUndefined();
  expect(init.headers).toEqual({ "X-CSRF-TOKEN": "csrf-token" });
});

test("a body-less POST sends an empty JSON object", async () => {
  answer(200, {});
  await send("POST", `/api/companies/${ID}/leave`);
  expect(fetchMock.mock.calls[1][1].body).toBe("{}");
});

test("a refusal keeps the problem code and Retry-After", async () => {
  answer(403, { code: "REAUTHENTICATION_REQUIRED", status: 403, title: "x", type: "about:blank" }, { "Retry-After": "30" });

  const result = await send("PATCH", `/api/companies/${ID}/members/${ID}`, { role: "ADMIN" });

  expect(result).toMatchObject({ ok: false, status: 403, retryAfter: 30, problem: { code: "REAUTHENTICATION_REQUIRED" } });
});

test("no CSRF token, or a network failure, is reported as unavailable and nothing is sent", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 500 }));
  expect(await send("POST", "/api/companies", { name: "Acme" })).toMatchObject({ ok: false, status: 503 });
  expect(fetchMock).toHaveBeenCalledTimes(1);

  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (String(url).includes("/csrf")) {
      return Response.json({ token: "t" });
    }
    throw new Error("offline");
  });
  expect(await send("POST", "/api/companies", { name: "Acme" })).toMatchObject({ ok: false, status: 503 });
});

test("authentication posts still use the pre-login token where they did before", async () => {
  answer(200, {});
  await post("login", { email: "a@example.com", password: "p" });
  expect(fetchMock.mock.calls[0][0]).toBe("/api/auth/csrf/pre-login");
  expect(fetchMock.mock.calls[1][0]).toBe("/api/auth/login");

  fetchMock.mockReset();
  answer(204);
  await post("step-up", { password: "p" });
  expect(fetchMock.mock.calls[0][0]).toBe("/api/auth/csrf");
  expect(fetchMock.mock.calls[1][0]).toBe("/api/auth/step-up");
});
