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
    await streamChat("c1", "hi", handlers);

    expect(handlers.onToken).toHaveBeenCalledWith("Hello");
  });

  it("dispatches multiple frames arriving in a single chunk, in order", async () => {
    const stream = sseStreamFromChunks([
      'event: token\ndata: {"t":"Hi"}\n\n' +
        'event: meta\ndata: {"conversationId":"c1","answer":"Hi","source":"llm","elapsedMs":5}\n\n',
    ]);
    global.fetch = vi.fn(async () => new Response(stream, { status: 200 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("c1", "hi", handlers);

    expect(handlers.onToken).toHaveBeenCalledWith("Hi");
    expect(handlers.onMeta).toHaveBeenCalledWith(
      expect.objectContaining({ conversationId: "c1", answer: "Hi", source: "llm" }),
    );
  });

  it("calls onError when the response is not ok, carrying the http status", async () => {
    global.fetch = vi.fn(async () => new Response(null, { status: 500 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("c1", "hi", handlers);

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
    await streamChat("c1", "hi", handlers);

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
    await streamChat("c1", "hi", handlers);

    expect(handlers.onError).not.toHaveBeenCalled();
    expect(handlers.onMeta).toHaveBeenCalled();
  });

  it("calls reauth once on a 401 and retries the request", async () => {
    let calls = 0;
    global.fetch = vi.fn(async () => {
      calls++;
      if (calls === 1) return new Response(null, { status: 401 });
      const stream = sseStreamFromChunks([
        'event: meta\ndata: {"conversationId":"c1","answer":"ok","source":"llm","elapsedMs":1}\n\n',
      ]);
      return new Response(stream, { status: 200 });
    }) as typeof fetch;

    const reauth = vi.fn(async () => true);
    const handlers = noopHandlers();
    await streamChat("c1", "hi", handlers, undefined, reauth);

    expect(reauth).toHaveBeenCalledTimes(1);
    expect(calls).toBe(2);
    expect(handlers.onMeta).toHaveBeenCalled();
    expect(handlers.onError).not.toHaveBeenCalled();
  });

  it("gives up with onError when a 401 reauth fails", async () => {
    global.fetch = vi.fn(async () => new Response(null, { status: 401 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("c1", "hi", handlers, undefined, async () => false);

    expect(handlers.onError).toHaveBeenCalledWith(
      expect.objectContaining({ kind: "http", status: 401 }),
    );
  });
});
