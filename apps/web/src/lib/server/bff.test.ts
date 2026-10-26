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
    origin: "https://evil.example",
  });
  const headers = upstreamHeaders(incoming, config, { json: true });
  expect(headers.get("x-bff-credential")).toBe("credential-for-tests");
  expect(headers.get("x-forwarded-for")).toBe("203.0.113.9");
  expect(headers.get("x-csrf-token")).toBe("tok");
  expect(headers.get("cookie")).toBe("__Host-session=abc; __Host-device=dev");
  expect(headers.get("content-type")).toBe("application/json");
  expect(headers.has("authorization")).toBe(false);
  expect(headers.has("x-forwarded-host")).toBe(false);
  expect(headers.get("origin")).toBe(config.publicOrigin);
});

test("an operation that is not on the allowlist is not forwarded", async () => {
  const response = await forward(
    browserRequest("sessions/revoke-all", { method: "POST", headers: { origin: config.publicOrigin, "content-type": "application/json" } }),
    ["sessions", "revoke-all"],
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

test("accepting an invitation reaches the API with the session, the CSRF token and the body, and nothing else", async () => {
  fetchMock.mockResolvedValue(Response.json({ id: "c", name: "Acme", role: "VIEWER" }));
  const response = await forward(
    browserRequest("invitations/accept", {
      method: "POST",
      headers: {
        origin: config.publicOrigin,
        "content-type": "application/json",
        "x-csrf-token": "tok",
        cookie: "__Host-session=abc; __Host-csrf=c; analytics=1",
      },
      body: '{"token":"t"}',
    }),
    ["invitations", "accept"],
    config,
  );
  expect(response.status).toBe(200);
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe("http://api.test/api/v1/invitations/accept");
  expect(init.method).toBe("POST");
  expect(init.body).toBe('{"token":"t"}');
  const headers = init.headers as Headers;
  expect(headers.get("cookie")).toBe("__Host-session=abc; __Host-csrf=c");
  expect(headers.get("x-csrf-token")).toBe("tok");
});

test("the invitation token is not accepted in a path", async () => {
  for (const path of [["invitations", "accept", "some-token"], ["invitations", "some-token"], ["invitations"]]) {
    const response = await forward(
      browserRequest(path.join("/"), {
        method: "POST",
        headers: { origin: config.publicOrigin, "content-type": "application/json" },
        body: "{}",
      }),
      path,
      config,
    );
    expect(response.status).toBe(404);
  }
  expect(fetchMock).not.toHaveBeenCalled();
});

test("accepting an invitation is not available as a GET", async () => {
  const response = await forward(browserRequest("invitations/accept"), ["invitations", "accept"], config);
  expect(response.status).toBe(404);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("an unknown CLIENT_IP_SOURCE stops the app instead of choosing a default", () => {
  expect(() => loadConfig({ BFF_CREDENTIAL: "x", CLIENT_IP_SOURCE: "header" } as unknown as NodeJS.ProcessEnv)).toThrow();
});

// ---- company operations (F1, D-172)

const C = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const M = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

const COMPANY_OPERATIONS: [string, string[], string][] = [
  ["POST", [], "/api/v1/companies"],
  ["POST", [C, "leave"], `/api/v1/companies/${C}/leave`],
  ["PATCH", [C, "members", M], `/api/v1/companies/${C}/members/${M}`],
  ["DELETE", [C, "members", M], `/api/v1/companies/${C}/members/${M}`],
  ["POST", [C, "members", M, "suspend"], `/api/v1/companies/${C}/members/${M}/suspend`],
  ["POST", [C, "members", M, "reactivate"], `/api/v1/companies/${C}/members/${M}/reactivate`],
  ["POST", [C, "invitations"], `/api/v1/companies/${C}/invitations`],
  ["POST", [C, "invitations", M, "resend"], `/api/v1/companies/${C}/invitations/${M}/resend`],
  ["DELETE", [C, "invitations", M], `/api/v1/companies/${C}/invitations/${M}`],
  ["POST", [C, "ownership-transfers"], `/api/v1/companies/${C}/ownership-transfers`],
  ["POST", [C, "ownership-transfers", M, "accept"], `/api/v1/companies/${C}/ownership-transfers/${M}/accept`],
  ["POST", [C, "ownership-transfers", M, "cancel"], `/api/v1/companies/${C}/ownership-transfers/${M}/cancel`],
];

function companyRequest(method: string, path: string[], extra: Record<string, string> = {}, body?: string) {
  const hasBody = method === "POST" || method === "PATCH";
  return new Request(`https://app.example.com/api/companies/${path.join("/")}`, {
    method,
    headers: {
      origin: config.publicOrigin,
      ...(hasBody ? { "content-type": "application/json" } : {}),
      "x-csrf-token": "tok",
      cookie: "__Host-session=abc; __Host-csrf=c; analytics=1",
      ...extra,
    },
    body: hasBody ? (body ?? "{}") : undefined,
  });
}

test.each(COMPANY_OPERATIONS)("%s /api/companies/%j reaches exactly %s", async (method, path, target) => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

  const response = await forward(companyRequest(method, path), path, config, "companies");

  expect(response.status).toBe(204);
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe(`http://api.test${target}`);
  expect(init.method).toBe(method);
  const headers = init.headers as Headers;
  expect(headers.get("cookie")).toBe("__Host-session=abc; __Host-csrf=c");
  expect(headers.get("x-csrf-token")).toBe("tok");
  expect(headers.get("x-bff-credential")).toBe("credential-for-tests");
});

test("the step-up operation is on the auth list and nothing else is added there", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  const response = await forward(
    browserRequest("step-up", {
      method: "POST",
      headers: { origin: config.publicOrigin, "content-type": "application/json", "x-csrf-token": "tok" },
      body: '{"password":"p"}',
    }),
    ["step-up"],
    config,
  );
  expect(response.status).toBe(204);
  expect(fetchMock.mock.calls[0][0]).toBe("http://api.test/api/v1/auth/step-up");
});

test("near misses are a 404 and never reach the API", async () => {
  const misses: [string, string[]][] = [
    ["POST", [C]],
    ["POST", [C, "leave", "extra"]],
    ["POST", ["not-a-uuid", "leave"]],
    ["POST", [C.slice(1), "leave"]],
    ["POST", [`${C}0`, "leave"]],
    ["POST", [`${C}%2F..`, "leave"]],
    ["POST", [`${C}/..`, "leave"]],
    ["POST", ["..", "leave"]],
    ["POST", ["", "leave"]],
    ["PATCH", [C, "members"]],
    ["PATCH", [C, "members", "not-a-uuid"]],
    ["PATCH", [C, "invitations", M]],
    ["DELETE", [C]],
    ["DELETE", [C, "members", M, "suspend"]],
    ["PUT", [C, "members", M]],
    ["GET", [C, "members"]],
    ["GET", []],
    ["POST", [C, "members", M, "role"]],
    ["POST", [C, "Members", M, "suspend"]],
    ["POST", [C, "invitations", M, "accept"]],
    ["DELETE", [C, "members", "{id}"]],
    // Reads of a transfer are done on the server; only the three changes are open (D-192).
    ["GET", [C, "ownership-transfer"]],
    ["GET", [C, "ownership-transfers", M]],
    ["POST", [C, "ownership-transfer"]],
    ["POST", [C, "ownership-transfers", M]],
    ["POST", [C, "ownership-transfers", M, "reject"]],
    ["POST", [C, "ownership-transfers", "not-a-uuid", "accept"]],
    ["POST", [C, "ownership-transfers", M, "accept", "extra"]],
    ["DELETE", [C, "ownership-transfers", M]],
    ["PATCH", [C, "ownership-transfers", M, "cancel"]],
  ];
  for (const [method, path] of misses) {
    const response = await forward(
      new Request("https://app.example.com/api/companies/x", {
        method,
        headers: { origin: config.publicOrigin, "content-type": "application/json" },
        body: method === "GET" ? undefined : "{}",
      }),
      path,
      config,
      "companies",
    );
    expect(response.status, `${method} ${JSON.stringify(path)}`).toBe(404);
  }
  expect(fetchMock).not.toHaveBeenCalled();
});

test("an upper-case id is accepted and sent in lower case", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  await forward(companyRequest("POST", [C.toUpperCase(), "leave"]), [C.toUpperCase(), "leave"], config, "companies");
  expect(fetchMock.mock.calls[0][0]).toBe(`http://api.test/api/v1/companies/${C}/leave`);
});

test("PATCH and DELETE from another origin, or marked cross-site, never reach the API", async () => {
  for (const method of ["PATCH", "DELETE"]) {
    const path = method === "PATCH" ? [C, "members", M] : [C, "invitations", M];
    const variants: Record<string, string>[] = [
      { origin: "https://evil.example" },
      { "sec-fetch-site": "cross-site" },
      { "sec-fetch-site": "same-site" },
    ];
    for (const extra of variants) {
      const response = await forward(companyRequest(method, path, extra), path, config, "companies");
      expect(response.status, `${method} ${JSON.stringify(extra)}`).toBe(403);
      expect((await response.json()).code).toBe("CSRF_REJECTED");
    }
    const noOrigin = new Request("https://app.example.com/api/companies/x", { method, body: method === "PATCH" ? "{}" : undefined, headers: method === "PATCH" ? { "content-type": "application/json" } : {} });
    expect((await forward(noOrigin, path, config, "companies")).status).toBe(403);
  }
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a PATCH without a JSON content type is rejected, and an over-size body is too", async () => {
  const path = [C, "members", M];
  const text = await forward(companyRequest("PATCH", path, { "content-type": "text/plain" }), path, config, "companies");
  expect(text.status).toBe(415);
  const big = await forward(companyRequest("PATCH", path, {}, "x".repeat(9000)), path, config, "companies");
  expect(big.status).toBe(413);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a DELETE sends no body even if the browser attached one, and no query string", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  const request = new Request(`https://app.example.com/api/companies/${C}/members/${M}?force=true`, {
    method: "DELETE",
    headers: { origin: config.publicOrigin, "content-type": "application/json", "x-csrf-token": "tok" },
    body: '{"force":true}',
  });

  await forward(request, [C, "members", M], config, "companies");

  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe(`http://api.test/api/v1/companies/${C}/members/${M}`);
  expect(init.body).toBeUndefined();
});

test("problem bodies, Retry-After and the session cookie from the API pass back for company operations", async () => {
  const upstream = new Response('{"code":"REAUTHENTICATION_REQUIRED"}', {
    status: 403,
    headers: { "Content-Type": "application/problem+json", "Retry-After": "5", "X-Internal": "secret" },
  });
  fetchMock.mockResolvedValue(upstream);
  const path = [C, "members", M];

  const response = await forward(companyRequest("PATCH", path), path, config, "companies");

  expect(response.status).toBe(403);
  expect(response.headers.get("retry-after")).toBe("5");
  expect(response.headers.get("x-internal")).toBeNull();
  expect(response.headers.get("cache-control")).toBe("no-store");
});

// ---- bounded request bodies (F1-5 security review)

test("a body that declares itself too large is refused without being read", async () => {
  let pulled = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      pulled += 1;
      controller.enqueue(new TextEncoder().encode("x".repeat(1024)));
    },
  });
  const request = new Request("https://app.example.com/api/companies", {
    method: "POST",
    headers: { origin: config.publicOrigin, "content-type": "application/json", "content-length": String(500 * 1024 * 1024) },
    body: stream,
    // @ts-expect-error Node needs this for a streamed request body
    duplex: "half",
  });

  const response = await forward(request, [], config, "companies");

  expect(response.status).toBe(413);
  expect(pulled).toBeLessThanOrEqual(1);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a body that does not say how large it is is read only until it passes the limit", async () => {
  let pulled = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      pulled += 1;
      controller.enqueue(new TextEncoder().encode("x".repeat(1024)));
    },
  });
  const request = new Request("https://app.example.com/api/companies", {
    method: "POST",
    headers: { origin: config.publicOrigin, "content-type": "application/json" },
    body: stream,
    // @ts-expect-error Node needs this for a streamed request body
    duplex: "half",
  });

  const response = await forward(request, [], config, "companies");

  expect(response.status).toBe(413);
  expect(pulled).toBeLessThan(40);
  expect(fetchMock).not.toHaveBeenCalled();
});

test("a body exactly at the limit is accepted and one byte over is not", async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  const at = await forward(companyRequest("POST", [], {}, "x".repeat(8 * 1024)), [], config, "companies");
  expect(at.status).toBe(204);
  const over = await forward(companyRequest("POST", [], {}, "x".repeat(8 * 1024 + 1)), [], config, "companies");
  expect(over.status).toBe(413);
});
