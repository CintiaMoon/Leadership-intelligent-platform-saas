import type { NextConfig } from "next";

// Pages that show or collect credentials, tokens or account details must never be stored by a cache.
const NO_STORE_PAGES = [
  "/",
  "/login",
  "/signup",
  "/check-email",
  "/verify-email",
  "/forgot-password",
  "/reset-password",
  "/account",
  "/invitations/accept",
];

const nextConfig: NextConfig = {
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "no-referrer" },
          // The full policy with a per-request nonce is added by src/proxy.ts for pages. This baseline also covers
          // the routes the proxy skips (API routes, static files).
          {
            key: "Content-Security-Policy",
            value: "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'",
          },
          // Browsers ignore HSTS over plain HTTP, so this only takes effect in production. The edge should send it too.
          { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=(), usb=()" },
          { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
        ],
      },
      ...NO_STORE_PAGES.map((source) => ({
        source,
        headers: [{ key: "Cache-Control", value: "no-store" }],
      })),
    ];
  },
};

export default nextConfig;
