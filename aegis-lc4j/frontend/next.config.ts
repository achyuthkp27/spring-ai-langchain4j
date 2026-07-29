import type { NextConfig } from "next";

// API proxying is handled by src/app/api/[...path]/route.ts, which probes for
// whichever Aegis backend is running (8082 merged → 8081 lc4j → 8080 aegis-ai).

const IS_PROD = process.env.NODE_ENV === "production";

// `connect-src 'self'` is the meaningful line: every fetch in the app is
// same-origin to /api/*, so this bounds where any injected script could
// exfiltrate to. Scripts/styles keep 'unsafe-inline' because Next's hydration
// and framer-motion's inline styles need it without nonce middleware; the JWT
// is no longer reachable from JS regardless (httpOnly cookie), so this is
// defence-in-depth. The CSP is production-only so it doesn't fight dev HMR.
const CSP = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "object-src 'none'",
].join("; ");

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  ...(IS_PROD
    ? [
        { key: "Content-Security-Policy", value: CSP },
        { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains; preload" },
      ]
    : []),
];

const nextConfig: NextConfig = {
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
