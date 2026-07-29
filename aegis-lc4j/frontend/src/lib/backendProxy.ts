// Server-only: shared backend discovery for the proxy routes. Not importable
// from client components (it reads process.env and has no "use client").

import type { NextRequest } from "next/server";

const IS_PROD = process.env.NODE_ENV === "production";

/**
 * True only on a positive cross-site signal. Defense-in-depth against CSRF for
 * cookie-authenticated, state-changing requests: `SameSite=Strict` is the
 * primary defense, this is the belt. Prefers the Fetch-Metadata header (browser
 * -set, unforgeable, proxy-safe); falls back to comparing the Origin host to the
 * Host header (both browser-supplied, so robust behind a reverse proxy). Absent
 * any signal — a same-origin GET, or a non-browser client — it does not block.
 */
export function isCrossSite(req: NextRequest): boolean {
  const site = req.headers.get("sec-fetch-site");
  if (site) return site === "cross-site";
  const origin = req.headers.get("origin");
  if (!origin) return false;
  try {
    return new URL(origin).host !== req.headers.get("host");
  } catch {
    return true;
  }
}

export const CANDIDATES = process.env.BACKEND_URL
  ? [process.env.BACKEND_URL]
  : IS_PROD
    ? []
    : ["http://localhost:8082", "http://localhost:8081", "http://localhost:8080"];

const PROBE_TIMEOUT_MS = 800;
const CACHE_TTL_MS = 10_000;

let cached: { base: string; at: number } | null = null;

async function probe(base: string): Promise<boolean> {
  try {
    const res = await fetch(`${base}/actuator/health`, {
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
      cache: "no-store",
    });
    return res.ok;
  } catch {
    return false;
  }
}

export async function liveBackend(force = false): Promise<string | null> {
  if (!force && cached && Date.now() - cached.at < CACHE_TTL_MS) return cached.base;
  for (const base of CANDIDATES) {
    if (await probe(base)) {
      cached = { base, at: Date.now() };
      return base;
    }
  }
  cached = null;
  return null;
}
