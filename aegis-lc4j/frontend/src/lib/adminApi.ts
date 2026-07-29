
const ADMIN_TOKEN_KEY = "aegis.admin.jwt";

interface CachedAdminToken {
  token: string;
  exp: number;
}

async function getAdminToken(): Promise<string> {
  if (typeof window !== "undefined") {
    const cached = sessionStorage.getItem(ADMIN_TOKEN_KEY);
    if (cached) {
      const { token, exp } = JSON.parse(cached) as CachedAdminToken;
      if (Date.now() < exp - 60_000) return token;
    }
  }
  const res = await fetch("/api/auth/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: "admin-user", tenantId: "achu-bank", role: "admin" }),
  });
  if (!res.ok) throw new Error(`admin auth failed: ${res.status}`);
  const data = (await res.json()) as { token: string };
  if (typeof window !== "undefined") {
    sessionStorage.setItem(ADMIN_TOKEN_KEY, JSON.stringify({ token: data.token, exp: Date.now() + 55 * 60_000 }));
  }
  return data.token;
}

async function adminGet<T>(path: string): Promise<T> {
  const token = await getAdminToken();
  const res = await fetch(`/api/admin${path}`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`admin request failed: ${res.status} ${path}`);
  return (await res.json()) as T;
}

export interface Overview {
  startedAt: string;
  uptimeMinutes: number;
  chatModel: string;
  requests: { total: number; llm: number; cache: number; blocked: number; cacheHitRate: number };
  bySource: Record<string, number>;
  latencyMs: Record<string, { count: number; avg: number; p50: number; p95: number; max: number }>;
  tokenSpend: { tenant: string; model: string; tokens: number }[];
  tenantBudgets: Record<string, { budgetConsumed: number }>;
  toolUsage: Record<string, Record<string, number>>;
  llmCircuit: string;
}

export interface TimeseriesPoint {
  minute: string;
  llm: number;
  cache: number;
  blocked: number;
  error: number;
}

export interface AuditEvent {
  id: number;
  createdAt: string;
  tenant: string;
  userId: string;
  conversationId: string;
  source: string;
  elapsedMs: number;
  answerChars: number;
  question: string;
}

export const fetchOverview = () => adminGet<Overview>("/overview");
export const fetchTimeseries = (minutes = 60) => adminGet<TimeseriesPoint[]>(`/timeseries?minutes=${minutes}`);
export const fetchAuditQuery = (params: { tenant?: string; from?: string; to?: string; limit?: number }) => {
  const q = new URLSearchParams();
  if (params.tenant) q.set("tenant", params.tenant);
  if (params.from) q.set("from", params.from);
  if (params.to) q.set("to", params.to);
  if (params.limit) q.set("limit", String(params.limit));
  return adminGet<AuditEvent[]>(`/audit/query?${q.toString()}`);
};
