import type { components, paths } from "./schema";

/** Browser-side calls to the BFF. Shapes come from the OpenAPI contract (contracts/openapi.yaml). */

export type Problem = components["schemas"]["Problem"];
export type FieldError = components["schemas"]["FieldError"];
export type SessionInfo = components["schemas"]["SessionInfo"];

type Body<P extends keyof paths> = paths[P] extends {
  post: { requestBody: { content: { "application/json": infer B } } };
}
  ? B
  : never;

export type SignupBody = Body<"/api/v1/auth/signup">;
export type LoginBody = Body<"/api/v1/auth/login">;
export type EmailBody = Body<"/api/v1/auth/verify-email/resend">;
export type TokenBody = Body<"/api/v1/auth/verify-email">;
export type ResetConfirmBody = Body<"/api/v1/auth/password-reset/confirm">;
export type AcceptInvitationBody = Body<"/api/v1/invitations/accept">;
export type CompanySummary = components["schemas"]["CompanySummary"];

export type ApiResult =
  | { ok: true; status: number; data: unknown }
  | { ok: false; status: number; problem: Problem | null; retryAfter: number | null };

const BASE = "/api/auth";

/** Operations that run before a session exists; they need the signed double-submit token (D-75). */
const PRE_LOGIN = new Set([
  "signup",
  "verify-email",
  "verify-email/resend",
  "login",
  "password-reset/request",
  "password-reset/confirm",
]);

async function csrfToken(preLogin: boolean): Promise<string | null> {
  try {
    const response = await fetch(`${BASE}/${preLogin ? "csrf/pre-login" : "csrf"}`, {
      cache: "no-store",
      credentials: "same-origin",
    });
    if (!response.ok) {
      return null;
    }
    const body = (await response.json()) as components["schemas"]["CsrfToken"];
    return body.token;
  } catch {
    return null;
  }
}

/**
 * POSTs JSON with a freshly issued CSRF token. The token is fetched per call so it is always current: the signed
 * double-submit token for operations that run before a session, the session-bound token for the rest.
 */
export async function post(path: string, body?: object): Promise<ApiResult> {
  const token = await csrfToken(PRE_LOGIN.has(path));
  if (!token) {
    return unavailable();
  }
  let response: Response;
  try {
    response = await fetch(`${BASE}/${path}`, {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
      body: JSON.stringify(body ?? {}),
    });
  } catch {
    return unavailable();
  }
  if (response.ok) {
    let data: unknown = null;
    try {
      data = await response.json();
    } catch {
      data = null;
    }
    return { ok: true, status: response.status, data };
  }
  let problem: Problem | null = null;
  try {
    problem = (await response.json()) as Problem;
  } catch {
    problem = null;
  }
  const retry = Number.parseInt(response.headers.get("retry-after") ?? "", 10);
  return {
    ok: false,
    status: response.status,
    problem,
    retryAfter: Number.isFinite(retry) ? retry : null,
  };
}

function unavailable(): ApiResult {
  return { ok: false, status: 503, problem: null, retryAfter: null };
}
