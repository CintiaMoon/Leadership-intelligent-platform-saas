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

/** The token, or the status that explains why there is none (401: the session has ended). */
async function csrfToken(preLogin: boolean): Promise<{ token: string } | { status: number }> {
  try {
    const response = await fetch(`${BASE}/${preLogin ? "csrf/pre-login" : "csrf"}`, {
      cache: "no-store",
      credentials: "same-origin",
    });
    if (!response.ok) {
      return { status: response.status };
    }
    const body = (await response.json()) as components["schemas"]["CsrfToken"];
    return { token: body.token };
  } catch {
    return { status: 0 };
  }
}

function noToken(status: number): ApiResult {
  // An ended session is reported as such, so the page can send the person to sign in instead of saying "busy".
  return status === 401 ? { ok: false, status: 401, problem: null, retryAfter: null } : unavailable();
}

/**
 * POSTs JSON to an authentication operation with a freshly issued CSRF token. The token is fetched per call so it is
 * always current: the signed double-submit token for operations that run before a session, the session-bound token
 * for the rest.
 */
export async function post(path: string, body?: object): Promise<ApiResult> {
  return send("POST", `${BASE}/${path}`, body, PRE_LOGIN.has(path));
}

/**
 * Sends a state-changing request to the BFF with the session-bound CSRF token (F1, D-172). `url` is a browser path
 * such as `/api/companies/{id}/members/{id}`; the BFF only forwards the operations it lists. A DELETE carries no body.
 */
export async function send(
  method: "POST" | "PATCH" | "DELETE",
  url: string,
  body?: object,
  preLogin = false,
): Promise<ApiResult> {
  const issued = await csrfToken(preLogin);
  if (!("token" in issued)) {
    return noToken(issued.status);
  }
  const headers: Record<string, string> = { "X-CSRF-TOKEN": issued.token };
  if (method !== "DELETE") {
    headers["Content-Type"] = "application/json";
  }
  let response: Response;
  try {
    response = await fetch(url, {
      method,
      cache: "no-store",
      credentials: "same-origin",
      headers,
      body: method === "DELETE" ? undefined : JSON.stringify(body ?? {}),
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
