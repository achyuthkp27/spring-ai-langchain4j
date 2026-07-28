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

// One persisted widget event attached to a historical assistant message — payload's shape
// depends on `type` ("cards" | "accounts" | "transactions" | "case" | "approval" | "citations"),
// mirroring the live SSE event of the same name. See WidgetHistoryStore (aegis-merged) for
// why this exists: without it, a page reload would silently lose every rich widget.
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

function writeCache(data: TokenResponse) {
  if (typeof window === "undefined") return;
  const session: CachedSession = { ...data, exp: Date.now() + 55 * 60_000 };
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

/** Current identity (userId/tenantId/role), minting a demo token if none is cached yet. */
export async function getProfile(): Promise<Profile> {
  const cached = readCache();
  if (cached) return { userId: cached.userId, tenantId: cached.tenantId, role: cached.role };
  const data = await mintToken();
  writeCache(data);
  return { userId: data.userId, tenantId: data.tenantId, role: data.role };
}

/** Switch to a different demo identity (tenant/user) — mints a fresh token and reloads so
    every component picks up the new session cleanly instead of trying to reconcile stale
    per-tenant local state (conversations, cached messages) in place. */
export async function switchIdentity(tenantId: string, userId: string): Promise<void> {
  const data = await mintToken({ tenantId, userId, role: "customer" });
  writeCache(data);
  if (typeof window !== "undefined") window.location.reload();
}

export async function fetchHistory(conversationId: string): Promise<HistoryMessage[]> {
  const token = await getToken();
  const res = await fetch(
    `/api/assistant/history?conversationId=${encodeURIComponent(conversationId)}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!res.ok) throw new Error(`history fetch failed: ${res.status}`);
  return (await res.json()) as HistoryMessage[];
}
