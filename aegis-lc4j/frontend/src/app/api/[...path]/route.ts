import { NextRequest } from "next/server";

const IS_PROD = process.env.NODE_ENV === "production";

const CANDIDATES = process.env.BACKEND_URL
  ? [process.env.BACKEND_URL]
  : IS_PROD
    ? []
    : ["http:

const PROBE_TIMEOUT_MS = 800;
const FORWARD_TIMEOUT_MS = 30_000;
const CACHE_TTL_MS = 10_000;
const SEGMENT_PATTERN = /^[A-Za-z0-9._-]+$/;
const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "transfer-encoding",
  "content-encoding",
  "content-length",
]);

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
    duplex: "half",
    cache: "no-store",
    signal: AbortSignal.timeout(FORWARD_TIMEOUT_MS),
  } as RequestInit & { duplex: "half" });

  const responseHeaders = new Headers();
  res.headers.forEach((value, key) => {
    if (!HOP_BY_HOP_HEADERS.has(key.toLowerCase())) responseHeaders.set(key, value);
  });
  if (!responseHeaders.has("content-type")) responseHeaders.set("content-type", "application/json");
  responseHeaders.set("cache-control", "no-cache");

  return new Response(res.body, { status: res.status, headers: responseHeaders });
}

async function handle(req: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  const { path } = await params;
  if (path.some((segment) => !SEGMENT_PATTERN.test(segment))) {
    return Response.json({ error: "Invalid path segment" }, { status: 400 });
  }
  const joined = path.join("/");

  if (CANDIDATES.length === 0) {
    return Response.json({ error: "BACKEND_URL is not configured" }, { status: 502 });
  }

  let base = await liveBackend();
  if (!base) {
    return Response.json({ error: "No Aegis backend is reachable" }, { status: 502 });
  }
  try {
    return await forward(req, base, joined);
  } catch {
    base = await liveBackend(true);
    if (!base) {
      return Response.json({ error: "No Aegis backend is reachable" }, { status: 502 });
    }
    return forward(req, base, joined);
  }
}

export { handle as GET, handle as POST, handle as PUT, handle as DELETE, handle as PATCH };
