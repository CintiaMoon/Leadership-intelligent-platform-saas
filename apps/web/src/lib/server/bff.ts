/**
 * The backend-for-frontend proxy (ADR-001). The browser talks only to this app; this module is the one place that
 * talks to the API. It forwards an allowlist of request headers and cookies, adds the BFF credential, passes the
 * API's cookies and a fixed set of response headers back, and never logs a body, cookie or token. The session lives
 * in the API; there is no second session store here.
 */
import { isIP } from "node:net";

/**
 * Where the client address comes from (D-95). `cloudfront`: the viewer address CloudFront adds, trusted because the
 * load balancer only accepts traffic from CloudFront. `rightmost`: the last entry of `X-Forwarded-For`, which the
 * load balancer (or, locally, the test) appended itself. The browser's own header is never forwarded as received.
 */
export type ClientIpSource = "cloudfront" | "rightmost";

export interface BffConfig {
  apiBaseUrl: string;
  credential: string;
  publicOrigin: string;
  csrfCookie: string;
  sessionCookie: string;
  deviceCookie: string;
  clientIpSource: ClientIpSource;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BffConfig {
  const credential = env.BFF_CREDENTIAL;
  if (!credential) {
    throw new Error("BFF_CREDENTIAL is not set");
  }
  const clientIpSource = env.CLIENT_IP_SOURCE ?? "rightmost";
  if (clientIpSource !== "cloudfront" && clientIpSource !== "rightmost") {
    throw new Error("CLIENT_IP_SOURCE must be cloudfront or rightmost");
  }
  return {
    apiBaseUrl: (env.API_BASE_URL ?? "http://localhost:8080").replace(/\/+$/, ""),
    credential,
    publicOrigin: new URL(env.PUBLIC_BASE_URL ?? "http://localhost:3000").origin,
    csrfCookie: env.CSRF_COOKIE_NAME ?? "__Host-csrf",
    sessionCookie: env.SESSION_COOKIE_NAME ?? "__Host-session",
    deviceCookie: env.DEVICE_COOKIE_NAME ?? "__Host-device",
    clientIpSource,
  };
}

/**
 * The only operations the browser may reach, keyed by "METHOD path" under /api/auth.
 *
 * `csrf/pre-login` asks the API for a token without the session cookie. A signed-in browser can still open a
 * verification or reset link, and those endpoints accept only the signed double-submit token, which the API issues
 * to callers that have no session.
 */
const ROUTES: Record<string, string> = {
  "GET csrf": "/api/v1/auth/csrf",
  "GET csrf/pre-login": "/api/v1/auth/csrf",
  "GET session": "/api/v1/auth/session",
  "POST signup": "/api/v1/auth/signup",
  "POST verify-email": "/api/v1/auth/verify-email",
  "POST verify-email/resend": "/api/v1/auth/verify-email/resend",
  "POST login": "/api/v1/auth/login",
  "POST logout": "/api/v1/auth/logout",
  "POST password-reset/request": "/api/v1/auth/password-reset/request",
  "POST password-reset/confirm": "/api/v1/auth/password-reset/confirm",
};

const MAX_BODY_BYTES = 8 * 1024;
const UPSTREAM_TIMEOUT_MS = 10_000;

function problem(status: number, code: string, title: string): Response {
  return Response.json(
    { type: "about:blank", title, status, code },
    {
      status,
      headers: {
        "Content-Type": "application/problem+json",
        "Cache-Control": "no-store",
      },
    },
  );
}

/** The named cookies from a Cookie header, and nothing else. */
export function pickCookies(header: string | null, names: string[]): string {
  if (!header) {
    return "";
  }
  return header
    .split(";")
    .map((part) => part.trim())
    .filter((part) => names.includes(part.slice(0, part.indexOf("="))))
    .join("; ");
}

/** The client address the edge vouches for, or null if there is none or it is not an IP address (D-95). */
export function clientAddress(incoming: Headers, source: ClientIpSource): string | null {
  let candidate: string | undefined;
  if (source === "cloudfront") {
    // "203.0.113.7:41234" or "[2001:db8::7]:41234"
    const viewer = incoming.get("cloudfront-viewer-address")?.trim();
    const match = viewer?.match(/^\[(.+)\]:\d+$/) ?? viewer?.match(/^([^:]+):\d+$/);
    candidate = match?.[1];
  } else {
    candidate = incoming.get("x-forwarded-for")?.split(",").pop()?.trim();
  }
  return candidate && isIP(candidate) ? candidate : null;
}

/** The headers sent to the API: a fixed allowlist, never a copy of the browser's headers. */
export function upstreamHeaders(
  incoming: Headers,
  config: BffConfig,
  options: { json: boolean; session?: boolean },
): Headers {
  const headers = new Headers({
    "X-BFF-Credential": config.credential,
    Accept: "application/json, application/problem+json",
  });
  if (options.json) {
    headers.set("Content-Type", "application/json");
  }
  const address = clientAddress(incoming, config.clientIpSource);
  if (address) {
    headers.set("X-Forwarded-For", address);
  }
  const csrf = incoming.get("x-csrf-token");
  if (csrf) {
    headers.set("X-CSRF-TOKEN", csrf);
  }
  const cookie = pickCookies(
    incoming.get("cookie"),
    options.session === false
      ? [config.csrfCookie, config.deviceCookie]
      : [config.csrfCookie, config.sessionCookie, config.deviceCookie],
  );
  if (cookie) {
    headers.set("Cookie", cookie);
  }
  return headers;
}

/** Forwards one allowed browser request to the API. */
export async function forward(
  request: Request,
  path: string[],
  config: BffConfig = loadConfig(),
): Promise<Response> {
  const key = `${request.method} ${path.join("/")}`;
  const target = ROUTES[key];
  if (!target) {
    return problem(404, "NOT_FOUND", "Not found");
  }
  const hasBody = request.method === "POST";
  if (hasBody) {
    // Only this site may send state-changing requests (D-75). A missing Origin is not accepted; browsers also
    // label every request with Sec-Fetch-Site, and anything but same-origin is refused.
    const fetchSite = request.headers.get("sec-fetch-site");
    if (
      request.headers.get("origin") !== config.publicOrigin ||
      (fetchSite !== null && fetchSite !== "same-origin")
    ) {
      return problem(403, "CSRF_REJECTED", "Request rejected");
    }
    if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
      return problem(415, "REQUEST_REJECTED", "Unsupported media type");
    }
  }

  let body: string | undefined;
  if (hasBody) {
    body = await request.text();
    if (new TextEncoder().encode(body).length > MAX_BODY_BYTES) {
      return problem(413, "REQUEST_REJECTED", "Request too large");
    }
  }

  let upstream: Response;
  try {
    upstream = await fetch(config.apiBaseUrl + target, {
      method: request.method,
      headers: upstreamHeaders(request.headers, config, {
        json: hasBody,
        session: key !== "GET csrf/pre-login",
      }),
      body,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch {
    return problem(503, "SERVICE_UNAVAILABLE", "Service unavailable");
  }

  const headers = new Headers({ "Cache-Control": "no-store" });
  const contentType = upstream.headers.get("content-type");
  if (contentType) {
    headers.set("Content-Type", contentType);
  }
  const retryAfter = upstream.headers.get("retry-after");
  if (retryAfter) {
    headers.set("Retry-After", retryAfter);
  }
  for (const cookie of upstream.headers.getSetCookie()) {
    headers.append("Set-Cookie", cookie);
  }
  return new Response(upstream.status === 204 ? null : upstream.body, {
    status: upstream.status,
    headers,
  });
}

export type SessionInfo = {
  userId: string;
  email: string;
  displayName: string;
  emailVerified: boolean;
  lastAuthenticatedAt: string;
  expiresAt: string;
};

/** Reads the current session for a server-rendered page. Null means there is no valid session. */
export async function loadSession(
  incoming: Headers,
  config: BffConfig = loadConfig(),
): Promise<SessionInfo | null> {
  const cookie = pickCookies(incoming.get("cookie"), [config.sessionCookie]);
  if (!cookie) {
    return null;
  }
  try {
    const response = await fetch(`${config.apiBaseUrl}/api/v1/auth/session`, {
      headers: upstreamHeaders(incoming, config, { json: false }),
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    return response.ok ? ((await response.json()) as SessionInfo) : null;
  } catch {
    return null;
  }
}
