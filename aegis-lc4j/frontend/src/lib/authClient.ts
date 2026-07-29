export interface Session {
  userId: string;
  tenantId: string;
  role: string;
}

interface CachedProfile extends Session {
  exp: number;
}

/**
 * One session lifecycle, shared by the customer app and the admin dashboard.
 *
 * The JWT never lives here: minting hits `/api/auth/token`, which sets it as an
 * httpOnly, SameSite=Strict cookie the browser's JS cannot read, and returns
 * only the non-secret profile. Same-origin requests carry the cookie
 * automatically and the proxy attaches it upstream, so nothing in client code
 * ever holds a bearer token — an XSS has no token to steal.
 *
 * `cacheKey` scopes the readable profile cache (not the credential) so the two
 * identities don't clobber each other's displayed user/tenant.
 */
export function createAuthClient(cacheKey: string, mintBody: Record<string, string> = {}) {
  const read = (): CachedProfile | null => {
    if (typeof window === "undefined") return null;
    try {
      const raw = sessionStorage.getItem(cacheKey);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as CachedProfile;
      // Refresh a minute early so an in-flight request never races expiry.
      if (Date.now() >= parsed.exp - 60_000) return null;
      return parsed;
    } catch {
      return null;
    }
  };

  const write = (p: CachedProfile) => {
    if (typeof window !== "undefined") sessionStorage.setItem(cacheKey, JSON.stringify(p));
  };

  const clear = () => {
    if (typeof window !== "undefined") sessionStorage.removeItem(cacheKey);
  };

  const mint = async (bodyOverride?: Record<string, string>): Promise<Session> => {
    const res = await fetch("/api/auth/token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(bodyOverride ?? mintBody),
      credentials: "same-origin",
    });
    if (!res.ok) throw new Error(`auth failed: ${res.status}`);
    const data = (await res.json()) as CachedProfile;
    write(data);
    return { userId: data.userId, tenantId: data.tenantId, role: data.role };
  };

  const getSession = async (): Promise<Session> => {
    const cached = read();
    return cached ? { userId: cached.userId, tenantId: cached.tenantId, role: cached.role } : mint();
  };

  /** Re-mint (refreshing the cookie); returns true so a caller can retry. */
  const reauth = async (): Promise<boolean> => {
    clear();
    try {
      await mint();
      return true;
    } catch {
      return false;
    }
  };

  /** Same-origin fetch (the cookie rides along); re-mints once on a 401 and retries. */
  const authFetch = async (url: string, init: RequestInit = {}): Promise<Response> => {
    const res = await fetch(url, { ...init, credentials: "same-origin" });
    if (res.status !== 401) return res;
    if (!(await reauth())) return res;
    return fetch(url, { ...init, credentials: "same-origin" });
  };

  return { getSession, reauth, authFetch, clear, mint };
}
