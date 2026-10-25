import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { readApi } from "./api";
import { forward, type BffConfig } from "./bff";

/**
 * AC-AUTH-10 and AC-INV-07 for the web tier: the BFF never writes passwords, cookies, CSRF values, invitation tokens,
 * addresses or request bodies to any log, on success or on any failure path. The strongest form of that is that it does
 * not log at all, which is what this checks, together with a scan of the server code for any console call.
 */

const config: BffConfig = {
  apiBaseUrl: "http://api.test",
  credential: "credential-for-tests",
  publicOrigin: "https://app.example.com",
  csrfCookie: "__Host-csrf",
  sessionCookie: "__Host-session",
  deviceCookie: "__Host-device",
  clientIpSource: "rightmost",
};

const C = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const M = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const SECRETS = ["hunter2-password-secret", "SESSIONVALUE-secret-cookie", "csrf-secret-value", "person@example.com", "invite-token-secret"];

const fetchMock = vi.fn();
const spies: ReturnType<typeof vi.spyOn>[] = [];

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  for (const method of ["log", "info", "warn", "error", "debug", "trace"] as const) {
    spies.push(vi.spyOn(console, method).mockImplementation(() => undefined));
  }
});

afterEach(() => {
  spies.splice(0).forEach((spy) => spy.mockRestore());
  vi.unstubAllGlobals();
});

function request(method: string, path: string[], body: string) {
  return new Request(`https://app.example.com/api/${path.join("/")}`, {
    method,
    headers: {
      origin: config.publicOrigin,
      "content-type": "application/json",
      "x-csrf-token": "csrf-secret-value",
      cookie: "__Host-session=SESSIONVALUE-secret-cookie; __Host-csrf=csrf-secret-value",
    },
    body: method === "DELETE" ? undefined : body,
  });
}

function logged(): string {
  return spies.flatMap((spy) => spy.mock.calls.map((call: unknown[]) => call.map(String).join(" "))).join("\n");
}

test("no operation, whatever the answer, writes anything to the console", async () => {
  const body = JSON.stringify({ password: "hunter2-password-secret", email: "person@example.com", token: "invite-token-secret" });
  const operations: [string, string[], "auth" | "companies"][] = [
    ["POST", ["step-up"], "auth"],
    ["POST", ["invitations", "accept"], "auth"],
    ["POST", [], "companies"],
    ["PATCH", [C, "members", M], "companies"],
    ["DELETE", [C, "members", M], "companies"],
    ["POST", [C, "invitations"], "companies"],
    ["DELETE", [C, "invitations", M], "companies"],
  ];
  const answers = [
    () => new Response(null, { status: 204 }),
    () => Response.json({ detail: "person@example.com hunter2-password-secret" }, { status: 400 }),
    () => new Response("upstream exploded with invite-token-secret", { status: 500 }),
    () => {
      throw new Error("connection refused hunter2-password-secret");
    },
  ];
  for (const [method, path, family] of operations) {
    for (const answer of answers) {
      fetchMock.mockReset();
      fetchMock.mockImplementation(async () => answer());
      await forward(request(method, path, body), path, config, family);
    }
  }
  // Refusals before the API is reached.
  await forward(request("PATCH", ["not-a-uuid", "members"], body), ["not-a-uuid", "members"], config, "companies");
  await forward(
    new Request("https://app.example.com/api/companies", { method: "POST", headers: { origin: "https://evil.example" }, body }),
    [],
    config,
    "companies",
  );

  expect(logged()).toBe("");
  for (const secret of SECRETS) {
    expect(logged()).not.toContain(secret);
  }
});

test("server reads write nothing to the console either, success or failure", async () => {
  const incoming = new Headers({ cookie: "__Host-session=SESSIONVALUE-secret-cookie" });
  fetchMock.mockResolvedValueOnce(Response.json([{ email: "person@example.com" }]));
  await readApi(incoming, "/api/v1/companies", config);
  fetchMock.mockResolvedValueOnce(new Response("person@example.com", { status: 500 }));
  await readApi(incoming, "/api/v1/companies", config);
  fetchMock.mockRejectedValueOnce(new Error("down SESSIONVALUE-secret-cookie"));
  await readApi(incoming, "/api/v1/companies", config);
  expect(logged()).toBe("");
});

test("the server code contains no console call at all", () => {
  const root = join(__dirname, "..", "..");
  const files: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) {
        walk(full);
      } else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) {
        files.push(full);
      }
    }
  };
  walk(join(root, "lib", "server"));
  walk(join(root, "app"));
  walk(join(root, "components"));
  const offenders = files.filter((file) => /\bconsole\.(log|info|warn|error|debug|trace)\b/.test(readFileSync(file, "utf8")));
  expect(files.length).toBeGreaterThan(20);
  expect(offenders).toEqual([]);
});
