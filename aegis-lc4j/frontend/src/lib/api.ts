import { createAuthClient } from "./authClient";

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

const client = createAuthClient("aegis.jwt");

export const getToken = () => client.getToken();

/** Re-mint after a 401, for callers (e.g. the SSE stream) that hold a token directly. */
export const refreshToken = () => client.refreshToken();

export async function getProfile(): Promise<Profile> {
  const { userId, tenantId, role } = await client.getSession();
  return { userId, tenantId, role };
}

export async function switchIdentity(tenantId: string, userId: string): Promise<void> {
  await client.mint({ tenantId, userId, role: "customer" });
  if (typeof window !== "undefined") window.location.reload();
}

export async function fetchHistory(conversationId: string): Promise<HistoryMessage[]> {
  const res = await client.authFetch(
    `/api/assistant/history?conversationId=${encodeURIComponent(conversationId)}`,
  );
  if (!res.ok) throw new Error(`history fetch failed: ${res.status}`);
  return (await res.json()) as HistoryMessage[];
}

export async function deleteConversation(conversationId: string): Promise<void> {
  const res = await client.authFetch(
    `/api/assistant/history?conversationId=${encodeURIComponent(conversationId)}`,
    { method: "DELETE" },
  );
  if (!res.ok) throw new Error(`delete conversation failed: ${res.status}`);
}
