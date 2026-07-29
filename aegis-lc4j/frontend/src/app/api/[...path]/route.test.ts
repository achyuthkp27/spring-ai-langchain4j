import { describe, it, expect, vi, beforeEach } from "vitest";
import { NextRequest } from "next/server";

process.env.BACKEND_URL = "http://backend-under-test:8082";

const { POST } = await import("./route");

function callParams(path: string[]) {
  return { params: Promise.resolve({ path }) };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe("proxy failover retry", () => {
  it("replays the original request body on retry instead of throwing 'body used already'", async () => {
    let forwardAttempts = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/actuator/health")) {
        return new Response(null, { status: 200 });
      }
      forwardAttempts++;
      if (forwardAttempts === 1) {
        throw new Error("simulated network failure on first attempt");
      }
      const bodyText = await new Response(init?.body as BodyInit).text();
      return new Response(JSON.stringify({ echoedBody: bodyText }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }) as typeof fetch;

    const originalBody = JSON.stringify({ conversationId: "c1", message: "hello" });
    const req = new NextRequest("http://proxy-host/api/assistant", {
      method: "POST",
      body: originalBody,
      headers: { "content-type": "application/json" },
    });

    const res = await POST(req, callParams(["assistant"]));

    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.echoedBody).toBe(originalBody);
    expect(forwardAttempts).toBe(2);
  });
});

describe("CSRF: cross-site mutating requests are blocked before forwarding", () => {
  it("rejects a POST with Sec-Fetch-Site: cross-site and never reaches the backend", async () => {
    let forwarded = false;
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes("/actuator/health")) return new Response(null, { status: 200 });
      forwarded = true;
      return new Response("{}", { status: 200 });
    }) as typeof fetch;

    const req = new NextRequest("http://proxy-host/api/assistant", {
      method: "POST",
      body: JSON.stringify({ conversationId: "c1", message: "hi" }),
      headers: { "content-type": "application/json", "sec-fetch-site": "cross-site" },
    });

    const res = await POST(req, callParams(["assistant"]));

    expect(res.status).toBe(403);
    expect(forwarded).toBe(false);
  });

  it("allows a same-origin POST (Sec-Fetch-Site: same-origin)", async () => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes("/actuator/health")) return new Response(null, { status: 200 });
      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }) as typeof fetch;

    const req = new NextRequest("http://proxy-host/api/assistant", {
      method: "POST",
      body: JSON.stringify({ conversationId: "c1", message: "hi" }),
      headers: { "content-type": "application/json", "sec-fetch-site": "same-origin" },
    });

    const res = await POST(req, callParams(["assistant"]));
    expect(res.status).toBe(200);
  });
});

describe("streaming requests are exempt from the whole-request timeout", () => {
  it("does not pass an AbortSignal for a text/event-stream request", async () => {
    let capturedSignal: AbortSignal | null | undefined;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/actuator/health")) {
        return new Response(null, { status: 200 });
      }
      capturedSignal = init?.signal;
      return new Response("data: hello\n\n", {
        status: 200,
        headers: { "content-type": "text/event-stream" },
      });
    }) as typeof fetch;

    const req = new NextRequest("http://proxy-host/api/assistant", {
      method: "POST",
      body: JSON.stringify({ conversationId: "c1", message: "hi" }),
      headers: { "content-type": "application/json", accept: "text/event-stream" },
    });

    const res = await POST(req, callParams(["assistant"]));

    expect(res.status).toBe(200);
    expect(capturedSignal).toBeUndefined();
  });

  it("does pass an AbortSignal for a plain JSON request", async () => {
    let capturedSignal: AbortSignal | null | undefined;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/actuator/health")) {
        return new Response(null, { status: 200 });
      }
      capturedSignal = init?.signal;
      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }) as typeof fetch;

    const req = new NextRequest("http://proxy-host/api/admin/overview", { method: "GET" });

    const res = await POST(req, callParams(["admin", "overview"]));

    expect(res.status).toBe(200);
    expect(capturedSignal).toBeInstanceOf(AbortSignal);
  });
});
