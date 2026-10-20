import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { clientAddress, forward, loadConfig, pickCookies, upstreamHeaders, type BffConfig } from "./bff";

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

function browserRequest(path: string, init: RequestInit = {}) {
  return new Request(`https://app.example.com/api/auth/${path}`, init);
}

test("only the session and csrf cookies are forwarded", () => {
  const header = "analytics=1; __Host-session=abc; other=2; __Host-csrf=tok; __Host-sessionX=no";
  expect(pickCookies(header, ["__Host-csrf", "__Host-session"])).toBe(
    "__Host-session=abc; __Host-csrf=tok",
  );
  expect(pickCookies(null, ["a"])).toBe("");
});

test("upstream headers are an allowlist and carry the BFF credential", () => {
  const incoming = new Headers({
    "x-forwarded-for": "198.51.100.1, 203.0.113.9",
    "x-csrf-token": "tok",
    cookie: "__Host-session=abc; tracker=1; __Host-device=dev",
    authorization: "Bearer should-not-pass",
    "x-bff-credential": "browser-supplied-value",
    "x-forwarded-host": "evil.example",
    origin: "https://app.example.com",
  });
  const headers = upstreamHeaders(incoming, config, { json: true });
  expect(headers.get("x-bff-credential")).toBe("credential-for-tests");
  expect(headers.get("x-forwarded-for")).toBe("203.0.113.9");
  expect(headers.get("x-csrf-token")).toBe("tok");
  expect(headers.get("cookie")).toBe("__Host-session=abc; __Host-device=dev");
  expect(headers.get("content-type")).toBe("application/json");
  expect(headers.has("authorization")).toBe(false);
  expect(headers.has("x-forwarded-host")).toBe(false);
  expect(headers.has("origin")).toBe(false);
});

test("an operation that is not on the allowlist is not forwarded", async () => {
  const response = await forward(
    browserRequest("step-up", { method: "POST", headers: { origin: config.publicOrigin, "content-type": "application/json" } }),
    ["step-up"],
    config,
  );
  expect(response.status).toBe(404);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a state-changing request from another origin, or without one, never reaches the API", async () => {
  for (const origin of ["https://evil.example", undefined]) {
    const headers: Record<string, string> = origin ? { origin } : {};
    const response = await forward(
      browserRequest("login", { method: "POST", headers, body: "{}" }),
      ["login"],
      config,
    );
    expect(response.status).toBe(403);
    expect((await response.json()).code).toBe("CSRF_REJECTED");
  }
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a request body over the limit is rejected", async () => {
  const response = await forward(
    browserRequest("login", {
      method: "POST",
      headers: { origin: config.publicOrigin, "content-type": "application/json" },
      body: "x".repeat(9000),
    }),
    ["login"],
    config,
  );
  expect(response.status).toBe(413);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("the response keeps status, problem body, Retry-After and every Set-Cookie, and is never cacheable", async () => {
  const upstream = new Response('{"code":"RATE_LIMITED"}', {
    status: 429,
    headers: {
      "Content-Type": "application/problem+json",
      "Retry-After": "30",
      "X-Internal": "secret",
    },
  });
  upstream.headers.append("Set-Cookie", "__Host-csrf=a; Path=/; HttpOnly");
  upstream.headers.append("Set-Cookie", "__Host-session=b; Path=/; HttpOnly");
  fetchMock.mockResolvedValue(upstream);

  const response = await forward(
    browserRequest("login", {
      method: "POST",
      headers: { origin: config.publicOrigin, "content-type": "application/json" },
      body: '{"email":"a"}',
    }),
    ["login"],
    config,
  );

  expect(response.status).toBe(429);
  expect(response.headers.get("retry-after")).toBe("30");
  expect(response.headers.get("cache-control")).toBe("no-store");
  expect(response.headers.get("content-type")).toBe("application/problem+json");
  expect(response.headers.has("x-internal")).toBe(false);
  expect(response.headers.getSetCookie()).toHaveLength(2);
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe("http://api.test/api/v1/auth/login");
  expect(init.body).toBe('{"email":"a"}');
  expect(init.redirect).toBe("manual");
});

test("an unreachable API becomes a generic 503 without details", async () => {
  fetchMock.mockRejectedValue(new Error("connect ECONNREFUSED 10.0.0.5:8080"));
  const response = await forward(browserRequest("csrf"), ["csrf"], config);
  expect(response.status).toBe(503);
  const text = await response.text();
  expect(text).not.toContain("10.0.0.5");
  expect(JSON.parse(text).code).toBe("SERVICE_UNAVAILABLE");
});

test("the pre-login token request leaves the session cookie out, the session one keeps it", async () => {
  fetchMock.mockImplementation(async () => Response.json({ token: "t" }));
  const cookie = "__Host-session=abc; __Host-csrf=old";

  await forward(browserRequest("csrf/pre-login", { headers: { cookie } }), ["csrf", "pre-login"], config);
  await forward(browserRequest("csrf", { headers: { cookie } }), ["csrf"], config);

  const [preLogin, withSession] = fetchMock.mock.calls.map(([, init]) => init.headers.get("cookie"));
  expect(preLogin).toBe("__Host-csrf=old");
  expect(withSession).toBe("__Host-session=abc; __Host-csrf=old");
});

test("a cross-site fetch or a non-JSON body never reaches the API, even with the right Origin", async () => {
  const send = (headers: Record<string, string>) =>
    forward(browserRequest("login", { method: "POST", headers, body: "{}" }), ["login"], config);
  const json = { origin: config.publicOrigin, "content-type": "application/json" };

  expect((await send({ ...json, "sec-fetch-site": "cross-site" })).status).toBe(403);
  expect((await send({ ...json, "sec-fetch-site": "same-site" })).status).toBe(403);
  expect((await send({ origin: config.publicOrigin, "content-type": "text/plain" })).status).toBe(415);
  expect((await send({ origin: config.publicOrigin })).status).toBe(415);
  expect(fetchMock).not.toHaveBeenCalled();

  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  expect((await send({ ...json, "sec-fetch-site": "same-origin" })).status).toBe(204);
});

test("the client address is set by the edge, never taken from what the browser put in the header", () => {
  // Rightmost: the load balancer appended the real peer after whatever the browser sent.
  const spoofed = new Headers({ "x-forwarded-for": "1.2.3.4, 5.6.7.8, 203.0.113.9" });
  expect(clientAddress(spoofed, "rightmost")).toBe("203.0.113.9");
  expect(upstreamHeaders(spoofed, config, { json: false }).get("x-forwarded-for")).toBe("203.0.113.9");

  // CloudFront: the viewer address, with or without brackets, ignoring the forwarded header entirely.
  const viaCloudFront = new Headers({
    "cloudfront-viewer-address": "203.0.113.7:41234",
    "x-forwarded-for": "9.9.9.9",
  });
  expect(clientAddress(viaCloudFront, "cloudfront")).toBe("203.0.113.7");
  expect(clientAddress(new Headers({ "cloudfront-viewer-address": "[2001:db8::7]:41234" }), "cloudfront")).toBe(
    "2001:db8::7",
  );

  // Anything that is not an address is dropped, so the API falls back to the peer rather than trusting text.
  for (const value of ["not-an-ip", "1.2.3.4; drop table", ""]) {
    expect(clientAddress(new Headers({ "x-forwarded-for": value }), "rightmost")).toBeNull();
  }
  expect(clientAddress(new Headers({ "x-forwarded-for": "1.2.3.4" }), "cloudfront")).toBeNull();
  expect(upstreamHeaders(new Headers(), config, { json: false }).has("x-forwarded-for")).toBe(false);
});

test("an unknown CLIENT_IP_SOURCE stops the app instead of choosing a default", () => {
  expect(() => loadConfig({ BFF_CREDENTIAL: "x", CLIENT_IP_SOURCE: "header" } as unknown as NodeJS.ProcessEnv)).toThrow();
});
