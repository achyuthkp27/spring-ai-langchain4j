#!/usr/bin/env python3
"""
Achu FinBot stress test — ramps 1..100 concurrent users against the streaming endpoint.
Each simulated user is its own tenant (so the per-tenant rate limiter doesn't mask the
shared-LLM behaviour we want to observe: Ollama serialises, so under load calls queue,
slow down, and the circuit breaker should shed load with fast 'busy' replies).

Measures per request: time-to-first-token (TTFT) and total time; classifies each outcome
(cache / llm / blocked / unavailable / error). Reports percentiles + outcome breakdown
per concurrency stage and polls the circuit-breaker state.

Pure stdlib. Usage: python3 loadtest.py
"""
import json, time, random, statistics, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = "http://localhost:8080"
STAGES = [1, 5, 10, 25, 50, 100]     # concurrent users per stage
STAGE_SECONDS = 20                    # closed-loop duration per stage
MAX_USERS = max(STAGES)

# Weighted request mix (realistic ops copilot traffic).
REQUESTS = (
    [("policy", q) for q in [
        "What is the deadline to file a card dispute?",
        "What are the KYC verification tiers?",
        "How much provisional credit can be issued without approval?",
        "What is the chargeback representment window?",
    ]] * 3 +                                   # ~ heavy: RAG + tool, some cache on repeat
    [("tool", "List the recent transactions for account ACC-1001")] * 2 +
    [("greet", "hi"), ("greet", "what can you do")] * 2 +      # fast-path, ~instant
    [("offdomain", "what is life"), ("offdomain", "write me a python script")] * 2  # blocked, fast
)

def mint(tenant):
    body = json.dumps({"userId": "u", "tenantId": tenant, "role": "disputes-analyst"}).encode()
    req = urllib.request.Request(BASE + "/api/auth/token", data=body,
                                 headers={"Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())["token"]

def circuit(token):
    req = urllib.request.Request(BASE + "/api/admin/circuit",
                                 headers={"Authorization": "Bearer " + token})
    try:
        return json.loads(urllib.request.urlopen(req, timeout=5).read())["llmCircuit"]
    except Exception:
        return "?"

def one_call(token, message):
    """Stream one request; return (ttft_ms, total_ms, outcome)."""
    body = json.dumps({"conversationId": "load", "message": message}).encode()
    req = urllib.request.Request(BASE + "/api/assistant", data=body, headers={
        "Content-Type": "application/json", "Accept": "text/event-stream",
        "Authorization": "Bearer " + token})
    t0 = time.time(); ttft = None; source = "llm"
    try:
        resp = urllib.request.urlopen(req, timeout=40)
        buf = ""
        while True:
            chunk = resp.read(256)
            if not chunk: break
            if ttft is None: ttft = (time.time() - t0) * 1000
            buf += chunk.decode(errors="ignore")
            # grab the meta event's source if present
            for blk in buf.split("\n\n"):
                if '"source"' in blk and "data:" in blk:
                    try: source = json.loads(blk.split("data:", 1)[1].strip()).get("source", source)
                    except Exception: pass
        return ttft or (time.time()-t0)*1000, (time.time()-t0)*1000, source
    except urllib.error.HTTPError as e:
        return None, (time.time()-t0)*1000, f"http{e.code}"
    except Exception:
        return None, (time.time()-t0)*1000, "error"

def pct(xs, p):
    if not xs: return 0
    xs = sorted(xs); k = int(round((p/100)*(len(xs)-1)))
    return xs[k]

def run_stage(users, tokens):
    deadline = time.time() + STAGE_SECONDS
    results = []
    def worker(uid):
        tok = tokens[uid]
        out = []
        while time.time() < deadline:
            kind, msg = random.choice(REQUESTS)
            out.append(one_call(tok, msg))
        return out
    with ThreadPoolExecutor(max_workers=users) as ex:
        futs = [ex.submit(worker, i) for i in range(users)]
        for f in as_completed(futs):
            results.extend(f.result())
    return results

def main():
    print(f"Minting {MAX_USERS} tenant tokens…")
    tokens = [mint(f"lt-{i}") for i in range(MAX_USERS)]
    admin = tokens[0]
    print(f"{'users':>5} {'req':>5} {'rps':>6} {'ttft_p50':>9} {'ttft_p95':>9} "
          f"{'tot_p50':>8} {'tot_p95':>8} {'tot_p99':>8}  outcomes / circuit")
    for users in STAGES:
        results = run_stage(users, tokens)
        n = len(results)
        ok = [r for r in results if r[2] in ("llm", "cache", "blocked")]
        ttfts = [r[0] for r in ok if r[0] is not None]
        totals = [r[1] for r in ok]
        from collections import Counter
        oc = Counter(r[2] for r in results)
        rps = n / STAGE_SECONDS
        outcomes = " ".join(f"{k}={v}" for k, v in sorted(oc.items()))
        print(f"{users:>5} {n:>5} {rps:>6.1f} {pct(ttfts,50):>9.0f} {pct(ttfts,95):>9.0f} "
              f"{pct(totals,50):>8.0f} {pct(totals,95):>8.0f} {pct(totals,99):>8.0f}  "
              f"[{circuit(admin)}] {outcomes}")

if __name__ == "__main__":
    main()
