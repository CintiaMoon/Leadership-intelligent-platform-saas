import { expect, test } from "vitest";

import { buildPolicy } from "./proxy";

test("scripts need the request's nonce and nothing else is allowed to run", () => {
  const policy = buildPolicy("abc123", false, true);
  const directive = (name: string) => policy.split("; ").find((part) => part.startsWith(`${name} `));

  expect(directive("script-src")).toBe("script-src 'self' 'nonce-abc123' 'strict-dynamic'");
  expect(policy).not.toContain("unsafe-inline");
  expect(policy).not.toContain("unsafe-eval");
  expect(directive("default-src")).toBe("default-src 'self'");
  expect(directive("object-src")).toBe("object-src 'none'");
  expect(directive("frame-ancestors")).toBe("frame-ancestors 'none'");
  expect(directive("form-action")).toBe("form-action 'self'");
  expect(policy).toContain("upgrade-insecure-requests");
});

test("development allows eval for React's debugging, plain HTTP does not upgrade requests", () => {
  expect(buildPolicy("n", true, true)).toContain("'unsafe-eval'");
  expect(buildPolicy("n", false, false)).not.toContain("upgrade-insecure-requests");
});
