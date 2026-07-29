export interface TokenResponse {
  token: string;
  userId: string;
  role: string;
  tenantId: string;
}

export interface Profile {
  userId: string;
  tenantId: string;
  role: string;
}

export interface HistoryWidgetEvent {
  type: string;
  payload: unknown;
}

export interface HistoryMessage {
  role: "user" | "assistant";
  text: string;
  widgets: HistoryWidgetEvent[];
}

const TOKEN_KEY = "aegis.jwt";

interface CachedSession {
  token: string;
  exp: number;
  userId: string;
  tenantId: string;
  role: string;
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

function readCache(): CachedSession | null {
  if (typeof window === "undefined") return null;
  try {
    const cached = sessionStorage.getItem(TOKEN_KEY);
    if (!cached) return null;
    const parsed = JSON.parse(cached) as CachedSession;
    if (Date.now() >= parsed.exp - 60_000) return null;
    return parsed;
  } catch {
    return null;
  }
}

function clearCache() {
  if (typeof window === "undefined") return;
  sessionStorage.removeItem(TOKEN_KEY);
}

function writeCache(data: TokenResponse) {
  if (typeof window === "undefined") return;
  const exp = decodeJwtExpMs(data.token) ?? Date.now() + 55 * 60_000;
  const session: CachedSession = { ...data, exp };
  sessionStorage.setItem(TOKEN_KEY, JSON.stringify(session));
}

async function mintToken(body: Partial<{ userId: string; tenantId: string; role: string }> = {}): Promise<TokenResponse> {
  const res = await fetch("/api/auth/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`auth failed: ${res.status}`);
  return (await res.json()) as TokenResponse;
}

export async function getToken(): Promise<string> {
  const cached = readCache();
  if (cached) return cached.token;
  const data = await mintToken();
  writeCache(data);
  return data.token;
}

export async function getProfile(): Promise<Profile> {
  const cached = readCache();
  if (cached) return { userId: cached.userId, tenantId: cached.tenantId, role: cached.role };
  const data = await mintToken();
  writeCache(data);
  return { userId: data.userId, tenantId: data.tenantId, role: data.role };
}

export async function switchIdentity(tenantId: string, userId: string): Promise<void> {
  const data = await mintToken({ tenantId, userId, role: "customer" });
  writeCache(data);
  if (typeof window !== "undefined") window.location.reload();
}

async function authFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const token = await getToken();
  const res = await fetch(url, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
  if (res.status !== 401) return res;

  clearCache();
  const freshToken = await getToken();
  return fetch(url, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${freshToken}` },
  });
}

export async function fetchHistory(conversationId: string): Promise<HistoryMessage[]> {
  const res = await authFetch(`/api/assistant/history?conversationId=${encodeURIComponent(conversationId)}`);
  if (!res.ok) throw new Error(`history fetch failed: ${res.status}`);
  return (await res.json()) as HistoryMessage[];
}

export async function deleteConversation(conversationId: string): Promise<void> {
  const res = await authFetch(`/api/assistant/history?conversationId=${encodeURIComponent(conversationId)}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error(`delete conversation failed: ${res.status}`);
}
