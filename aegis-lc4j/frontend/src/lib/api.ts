export interface TokenResponse {
  token: string;
  userId: string;
  role: string;
  tenantId: string;
}

export interface HistoryMessage {
  role: "user" | "assistant";
  text: string;
}

const TOKEN_KEY = "aegis.jwt";

export async function getToken(): Promise<string> {
  if (typeof window !== "undefined") {
    const cached = sessionStorage.getItem(TOKEN_KEY);
    if (cached) {
      const { token, exp } = JSON.parse(cached) as { token: string; exp: number };
      if (Date.now() < exp - 60_000) return token;
    }
  }
  const res = await fetch("/api/auth/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({}), // demo customer defaults
  });
  if (!res.ok) throw new Error(`auth failed: ${res.status}`);
  const data = (await res.json()) as TokenResponse;
  if (typeof window !== "undefined") {
    sessionStorage.setItem(
      TOKEN_KEY,
      JSON.stringify({ token: data.token, exp: Date.now() + 55 * 60_000 }),
    );
  }
  return data.token;
}

export async function fetchHistory(conversationId: string): Promise<HistoryMessage[]> {
  const token = await getToken();
  const res = await fetch(
    `/api/assistant/history?conversationId=${encodeURIComponent(conversationId)}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!res.ok) return [];
  return (await res.json()) as HistoryMessage[];
}
