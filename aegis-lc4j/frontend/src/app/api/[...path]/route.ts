import { NextRequest } from "next/server";

/**
 * Dynamic backend proxy: forwards /api/* to whichever Aegis backend is running,
 * probing in priority order (merged → lc4j → original aegis-ai). The winner is
 * cached briefly so every request doesn't pay the probe cost. SSE responses are
 * streamed through untouched.
 */
const CANDIDATES = process.env.BACKEND_URL
  ? [process.env.BACKEND_URL]
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

async function liveBackend(force = false): Promise<string | null> {
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

async function forward(req: NextRequest, base: string, path: string): Promise<Response> {
  const url = `${base}/api/${path}${req.nextUrl.search}`;
  const headers: Record<string, string> = {};
  for (const h of ["authorization", "content-type", "accept"]) {
    const v = req.headers.get(h);
    if (v) headers[h] = v;
  }
  const res = await fetch(url, {
    method: req.method,
    headers,
    body: req.method === "GET" || req.method === "HEAD" ? undefined : req.body,
    // @ts-expect-error duplex is required by Node fetch for streamed request bodies
    duplex: "half",
    cache: "no-store",
  });
  // Pass the body stream through (SSE-safe) with the backend's content type.
  return new Response(res.body, {
    status: res.status,
    headers: {
      "content-type": res.headers.get("content-type") ?? "application/json",
      "cache-control": "no-cache",
    },
  });
}

async function handle(req: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  const { path } = await params;
  const joined = path.join("/");
  let base = await liveBackend();
  if (!base) {
    return Response.json({ error: "No Aegis backend is running (tried 8082, 8081, 8080)" }, { status: 502 });
  }
  try {
    return await forward(req, base, joined);
  } catch {
    // Cached backend may have just gone down — re-probe once and retry.
    base = await liveBackend(true);
    if (!base) {
      return Response.json({ error: "No Aegis backend is running (tried 8082, 8081, 8080)" }, { status: 502 });
    }
    return forward(req, base, joined);
  }
}

export { handle as GET, handle as POST, handle as PUT, handle as DELETE, handle as PATCH };
