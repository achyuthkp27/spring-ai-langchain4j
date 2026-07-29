import { NextRequest } from "next/server";
import { liveBackend } from "@/lib/backendProxy";

// Takes precedence over the [...path] catch-all for POST /api/auth/token.
export const dynamic = "force-dynamic";

const IS_PROD = process.env.NODE_ENV === "production";
const DEFAULT_TTL_S = 3300;

// Two identities can be live in one browser (the customer app and the admin
// dashboard), so they get separate cookies; the proxy selects by path.
const USER_COOKIE = "aegis_session";
const ADMIN_COOKIE = "aegis_admin_session";

interface MintedToken {
  token: string;
  userId: string;
  tenantId: string;
  role: string;
}

function tokenTtlSeconds(token: string): number {
  try {
    const json = atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"));
    const exp = (JSON.parse(json) as { exp?: number }).exp;
    if (typeof exp === "number") {
      const remaining = exp - Math.floor(Date.now() / 1000);
      if (remaining > 0) return remaining;
    }
  } catch {
    /* fall through to default */
  }
  return DEFAULT_TTL_S;
}

/**
 * Mints a session against the backend and returns only the non-secret profile.
 * The JWT itself is set as an httpOnly, SameSite=Strict cookie the browser's JS
 * can never read — the proxy attaches it to upstream requests. This is what
 * keeps a token out of reach of any XSS.
 */
export async function POST(req: NextRequest): Promise<Response> {
  const base = await liveBackend();
  if (!base) return Response.json({ error: "No Aegis backend is reachable" }, { status: 502 });

  const body = await req.text();
  let upstream: Response;
  try {
    upstream = await fetch(`${base}/api/auth/token`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    return Response.json({ error: "Auth service unreachable" }, { status: 502 });
  }

  if (!upstream.ok) {
    return new Response(await upstream.text(), {
      status: upstream.status,
      headers: { "content-type": upstream.headers.get("content-type") ?? "application/json" },
    });
  }

  const data = (await upstream.json()) as MintedToken;
  const isAdmin = data.role !== "customer" && data.role !== "read-only";
  const cookieName = isAdmin ? ADMIN_COOKIE : USER_COOKIE;
  const ttl = tokenTtlSeconds(data.token);
  const expMs = Date.now() + ttl * 1000;

  const cookie =
    `${cookieName}=${data.token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${ttl}` +
    (IS_PROD ? "; Secure" : "");

  const res = Response.json({
    userId: data.userId,
    tenantId: data.tenantId,
    role: data.role,
    exp: expMs,
  });
  res.headers.append("set-cookie", cookie);
  return res;
}
