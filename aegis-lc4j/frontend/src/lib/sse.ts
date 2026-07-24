/**
 * SSE-over-POST client: fetch + ReadableStream (EventSource can't POST).
 * Parses `event:`/`data:` frames and dispatches typed callbacks.
 */

export interface ChatMeta {
  conversationId: string;
  answer: string;
  source: "llm" | "cache" | "blocked" | "unavailable";
  elapsedMs: number;
  similarity?: number | null;
  matchedQuestion?: string | null;
}

export interface StreamHandlers {
  onToken: (text: string) => void;
  onStatus: (status: string) => void;
  onMeta: (meta: ChatMeta) => void;
  onError: (err: Error) => void;
}

export async function streamChat(
  token: string,
  conversationId: string,
  message: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response;
  try {
    res = await fetch("/api/assistant", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        Accept: "text/event-stream",
      },
      body: JSON.stringify({ conversationId, message }),
      signal,
    });
  } catch (e) {
    handlers.onError(e instanceof Error ? e : new Error(String(e)));
    return;
  }
  if (!res.ok || !res.body) {
    handlers.onError(new Error(`assistant request failed: ${res.status}`));
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const dispatch = (event: string, data: string) => {
    try {
      const payload = JSON.parse(data);
      if (event === "token" && typeof payload.t === "string") handlers.onToken(payload.t);
      else if (event === "status" && typeof payload.s === "string") handlers.onStatus(payload.s);
      else if (event === "meta") handlers.onMeta(payload as ChatMeta);
    } catch {
      // skip malformed frame
    }
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // Frames are separated by a blank line.
      let sep: number;
      while ((sep = buffer.indexOf("\n\n")) >= 0) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        let event = "message";
        const dataLines: string[] = [];
        for (const line of frame.split("\n")) {
          if (line.startsWith("event:")) event = line.slice(6).trim();
          else if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
        }
        if (dataLines.length) dispatch(event, dataLines.join("\n"));
      }
    }
  } catch (e) {
    if ((e as DOMException)?.name !== "AbortError") {
      handlers.onError(e instanceof Error ? e : new Error(String(e)));
    }
  }
}
