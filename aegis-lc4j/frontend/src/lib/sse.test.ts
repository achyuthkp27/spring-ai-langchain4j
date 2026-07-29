import { describe, it, expect, vi, beforeEach } from "vitest";
import { streamChat, type StreamHandlers } from "./sse";

function sseStreamFromChunks(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let i = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(encoder.encode(chunks[i]));
        i++;
      } else {
        controller.close();
      }
    },
  });
}

function noopHandlers(): StreamHandlers {
  return {
    onToken: vi.fn(),
    onStatus: vi.fn(),
    onCards: vi.fn(),
    onAccounts: vi.fn(),
    onTransactions: vi.fn(),
    onCase: vi.fn(),
    onApproval: vi.fn(),
    onCitations: vi.fn(),
    onLedger: vi.fn(),
    onProfile: vi.fn(),
    onStatement: vi.fn(),
    onMeta: vi.fn(),
    onError: vi.fn(),
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe("streamChat frame parsing", () => {
  it("dispatches a token event whose data line is split across two stream chunks", async () => {
    const stream = sseStreamFromChunks([
      'event: token\ndata: {"t":"Hel',
      'lo"}\n\n',
    ]);
    global.fetch = vi.fn(async () => new Response(stream, { status: 200 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("token", "c1", "hi", handlers);

    expect(handlers.onToken).toHaveBeenCalledWith("Hello");
  });

  it("dispatches multiple frames arriving in a single chunk, in order", async () => {
    const stream = sseStreamFromChunks([
      'event: token\ndata: {"t":"Hi"}\n\n' +
        'event: meta\ndata: {"conversationId":"c1","answer":"Hi","source":"llm","elapsedMs":5}\n\n',
    ]);
    global.fetch = vi.fn(async () => new Response(stream, { status: 200 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("token", "c1", "hi", handlers);

    expect(handlers.onToken).toHaveBeenCalledWith("Hi");
    expect(handlers.onMeta).toHaveBeenCalledWith(
      expect.objectContaining({ conversationId: "c1", answer: "Hi", source: "llm" }),
    );
  });

  it("calls onError when the response is not ok, carrying the http status", async () => {
    global.fetch = vi.fn(async () => new Response(null, { status: 500 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("token", "c1", "hi", handlers);

    expect(handlers.onError).toHaveBeenCalledWith(
      expect.objectContaining({ kind: "http", status: 500 }),
    );
  });

  it("emits a terminal onError when the body closes without a meta event", async () => {
    // A stream that delivers a token then closes — no `meta`. Without a terminal
    // guarantee this would leave the bubble stuck in `streaming: true` forever.
    const stream = sseStreamFromChunks(['event: token\ndata: {"t":"partial"}\n\n']);
    global.fetch = vi.fn(async () => new Response(stream, { status: 200 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("token", "c1", "hi", handlers);

    expect(handlers.onToken).toHaveBeenCalledWith("partial");
    expect(handlers.onMeta).not.toHaveBeenCalled();
    expect(handlers.onError).toHaveBeenCalledWith(expect.objectContaining({ kind: "stream" }));
  });

  it("ignores an unknown event name and still terminates the turn", async () => {
    const stream = sseStreamFromChunks([
      'event: sparkle\ndata: {"whatever":true}\n\n' +
        'event: meta\ndata: {"conversationId":"c1","answer":"ok","source":"llm","elapsedMs":1}\n\n',
    ]);
    global.fetch = vi.fn(async () => new Response(stream, { status: 200 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("token", "c1", "hi", handlers);

    expect(handlers.onError).not.toHaveBeenCalled();
    expect(handlers.onMeta).toHaveBeenCalled();
  });

  it("re-auths once on a 401 and retries with the fresh token", async () => {
    const seenAuth: string[] = [];
    global.fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const auth = (init?.headers as Record<string, string>)?.Authorization ?? "";
      seenAuth.push(auth);
      if (auth === "Bearer stale") return new Response(null, { status: 401 });
      const stream = sseStreamFromChunks([
        'event: meta\ndata: {"conversationId":"c1","answer":"ok","source":"llm","elapsedMs":1}\n\n',
      ]);
      return new Response(stream, { status: 200 });
    }) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("stale", "c1", "hi", handlers, undefined, async () => "fresh");

    expect(seenAuth).toEqual(["Bearer stale", "Bearer fresh"]);
    expect(handlers.onMeta).toHaveBeenCalled();
    expect(handlers.onError).not.toHaveBeenCalled();
  });
});
