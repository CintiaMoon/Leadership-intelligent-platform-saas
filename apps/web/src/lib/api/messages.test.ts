import { expect, test } from "vitest";

import type { ApiResult, Problem } from "./client";
import { describeFailure } from "./messages";

function failure(
  status: number,
  problem: Partial<Problem> | null,
  retryAfter: number | null = null,
): Extract<ApiResult, { ok: false }> {
  return { ok: false, status, problem: problem as Problem | null, retryAfter };
}

test("field errors map to field messages and leave the form message empty", () => {
  const result = describeFailure(
    failure(400, {
      code: "VALIDATION_FAILED",
      errors: [
        { field: "password", code: "BREACHED_PASSWORD" },
        { field: "email", code: "INVALID_EMAIL" },
      ],
    }),
  );
  expect(result.form).toBeNull();
  expect(result.fields.password).toMatch(/known data breaches/);
  expect(result.fields.email).toMatch(/valid email/);
});

test("every login failure reads the same, whatever the reason", () => {
  const result = describeFailure(failure(401, { code: "INVALID_CREDENTIALS" }), "login");
  expect(result.form).toMatch(/email or password is incorrect/);
  expect(result.form).not.toMatch(/exist|unverified|disabled|locked|blocked/i);
});

test("a token problem and a throttle each get their own message", () => {
  expect(describeFailure(failure(422, { code: "TOKEN_INVALID" })).form).toMatch(
    /invalid or has expired/,
  );
  expect(describeFailure(failure(429, { code: "RATE_LIMITED" }, 600)).form).toMatch(
    /about 10 minutes/,
  );
  expect(describeFailure(failure(503, null, 1)).form).toMatch(/busy/);
});

test("an unknown failure gets a generic message", () => {
  expect(describeFailure(failure(500, null)).form).toMatch(/Something went wrong/);
});
