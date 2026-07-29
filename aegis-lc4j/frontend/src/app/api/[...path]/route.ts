import { NextRequest } from "next/server";
import { CANDIDATES, isCrossSite, liveBackend } from "@/lib/backendProxy";

// This route proxies SSE; it must never be statically evaluated, and it needs a
// generous ceiling so a long streamed turn isn't cut off by the platform's
// default function timeout (10–15s on Vercel).
export const dynamic = "force-dynamic";
export const maxDuration = 60;

const FORWARD_TIMEOUT_MS = 30_000;
const SEGMENT_PATTERN = /^[A-Za-z0-9._-]+$/;
const MUTATING_METHODS = new Set(["POST", "PUT", "DELETE", "PATCH"]);
const USER_COOKIE = "aegis_session";
const ADMIN_COOKIE = "aegis_admin_session";
const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "transfer-encoding",
  "content-encoding",
  "content-length",
]);

function isStreamingRequest(req: NextRequest): boolean {
  return (req.headers.get("accept") ?? "").includes("text/event-stream");
}

/** The bearer comes from the httpOnly session cookie, never from a client header. */
function bearerFor(req: NextRequest, path: string): string | null {
  const name = path.startsWith("admin/") ? ADMIN_COOKIE : USER_COOKIE;
  return req.cookies.get(name)?.value ?? null;
}

async function forward(
  req: NextRequest,
  base: string,
  path: string,
  body: ArrayBuffer | undefined,
): Promise<Response> {
  const url = `${base}/api/${path}${req.nextUrl.search}`;
  const headers: Record<string, string> = {};
  for (const h of ["content-type", "accept"]) {
    const v = req.headers.get(h);
    if (v) headers[h] = v;
  }
  const bearer = bearerFor(req, path);
  if (bearer) headers.authorization = `Bearer ${bearer}`;
  const streaming = isStreamingRequest(req);
  const res = await fetch(url, {
    method: req.method,
    headers,
    body,
    cache: "no-store",
    signal: streaming ? undefined : AbortSignal.timeout(FORWARD_TIMEOUT_MS),
  });

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
  // CSRF defense-in-depth: the session cookie is ambient, so a cross-site
  // mutating request must be rejected before the bearer is injected upstream.
  if (MUTATING_METHODS.has(req.method) && isCrossSite(req)) {
    return Response.json({ error: "Cross-site request blocked" }, { status: 403 });
  }
  const joined = path.join("/");

  if (CANDIDATES.length === 0) {
    return Response.json({ error: "BACKEND_URL is not configured" }, { status: 502 });
  }

  let base = await liveBackend();
  if (!base) {
    return Response.json({ error: "No Aegis backend is reachable" }, { status: 502 });
  }

  const hasBody = req.method !== "GET" && req.method !== "HEAD";
  const body = hasBody ? await req.arrayBuffer() : undefined;

  try {
    return await forward(req, base, joined, body);
  } catch {
    base = await liveBackend(true);
    if (!base) {
      return Response.json({ error: "No Aegis backend is reachable" }, { status: 502 });
    }
    return forward(req, base, joined, body);
  }
}

export { handle as GET, handle as POST, handle as PUT, handle as DELETE, handle as PATCH };
