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

const client = createAuthClient("aegis.session");

/** Ensure a session cookie exists (minting one if needed) before an authed request. */
export const ensureSession = () => client.getSession();

/** Re-mint after a 401, for the SSE stream to retry. Returns true on success. */
export const reauth = () => client.reauth();

export async function getProfile(): Promise<Profile> {
  return client.getSession();
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
