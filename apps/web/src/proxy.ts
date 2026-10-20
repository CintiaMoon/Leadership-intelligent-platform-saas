import { NextResponse, type NextRequest } from "next/server";

/**
 * Content Security Policy with a fresh nonce per request (Phase 6, SEC-L7). Scripts run only if they carry the nonce
 * (or were loaded by one that does, `strict-dynamic`), so an injected `<script>` or an inline handler does nothing.
 * Next.js reads the nonce from this header while rendering and puts it on its own scripts and styles.
 *
 * `CSP_MODE=report-only` sends the same policy as `Content-Security-Policy-Report-Only` for a trial period; any
 * other value, or none, enforces it.
 */
export function buildPolicy(nonce: string, isDev: boolean, https: boolean): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${isDev ? " 'unsafe-eval'" : ""}`,
    `style-src 'self' 'nonce-${nonce}'`,
    "img-src 'self' blob: data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    // Only where the site is served over HTTPS; on plain-HTTP local runs it would rewrite every request to https.
    ...(https && !isDev ? ["upgrade-insecure-requests"] : []),
  ].join("; ");
}

export function proxy(request: NextRequest) {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const policy = buildPolicy(
    nonce,
    process.env.NODE_ENV === "development",
    (process.env.PUBLIC_BASE_URL ?? "").startsWith("https://"),
  );
  const header =
    process.env.CSP_MODE === "report-only"
      ? "Content-Security-Policy-Report-Only"
      : "Content-Security-Policy";

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set(header, policy);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set(header, policy);
  return response;
}

export const config = {
  matcher: [
    {
      source: "/((?!api|_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
