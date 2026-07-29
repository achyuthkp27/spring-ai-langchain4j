export interface Session {
  token: string;
  userId: string;
  tenantId: string;
  role: string;
}

interface CachedSession extends Session {
  exp: number;
}

function decodeJwtExpMs(token: string): number | null {
  try {
    const payload = token.split(".")[1];
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    const claims = JSON.parse(json) as { exp?: number };
    return typeof claims.exp === "number" ? claims.exp * 1000 : null;
  } catch {
    return null;
  }
}

/**
 * One token lifecycle, shared by the customer app and the admin dashboard.
 *
 * Both previously reinvented this and diverged: the admin copy estimated `exp`
 * instead of decoding it and had no 401 retry, so a stale admin token failed
 * the 15s poll forever. Routing both through here means one real-`exp` decode
 * and one 401-retry policy.
 */
export function createAuthClient(cacheKey: string, mintBody: Record<string, string> = {}) {
  const read = (): CachedSession | null => {
    if (typeof window === "undefined") return null;
    try {
      const raw = sessionStorage.getItem(cacheKey);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as CachedSession;
      // Refresh a minute early so an in-flight request never races expiry.
      if (Date.now() >= parsed.exp - 60_000) return null;
      return parsed;
    } catch {
      return null;
    }
  };

  const write = (data: Session): Session => {
    if (typeof window === "undefined") return data;
    const exp = decodeJwtExpMs(data.token) ?? Date.now() + 55 * 60_000;
    sessionStorage.setItem(cacheKey, JSON.stringify({ ...data, exp }));
    return data;
  };

  const clear = () => {
    if (typeof window !== "undefined") sessionStorage.removeItem(cacheKey);
  };

  const mint = async (bodyOverride?: Record<string, string>): Promise<Session> => {
    const res = await fetch("/api/auth/token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(bodyOverride ?? mintBody),
    });
    if (!res.ok) throw new Error(`auth failed: ${res.status}`);
    return write((await res.json()) as Session);
  };

  const getSession = async (): Promise<Session> => read() ?? (await mint());
  const getToken = async (): Promise<string> => (await getSession()).token;

  const refreshToken = async (): Promise<string> => {
    clear();
    return getToken();
  };

  /** Fetch with the bearer token, re-minting once on a 401. */
  const authFetch = async (url: string, init: RequestInit = {}): Promise<Response> => {
    const token = await getToken();
    const res = await fetch(url, {
      ...init,
      headers: { ...init.headers, Authorization: `Bearer ${token}` },
    });
    if (res.status !== 401) return res;

    const fresh = await refreshToken();
    return fetch(url, {
      ...init,
      headers: { ...init.headers, Authorization: `Bearer ${fresh}` },
    });
  };

  return { getSession, getToken, refreshToken, authFetch, clear, mint };
}
