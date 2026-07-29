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

  it("calls onError when the response is not ok", async () => {
    global.fetch = vi.fn(async () => new Response(null, { status: 500 })) as typeof fetch;

    const handlers = noopHandlers();
    await streamChat("token", "c1", "hi", handlers);

    expect(handlers.onError).toHaveBeenCalled();
  });
});
