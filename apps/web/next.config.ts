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
];

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
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
          // A script-src policy needs per-request nonces; it is part of the Phase 6 hardening review.
          {
            key: "Content-Security-Policy",
            value: "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'",
          },
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
