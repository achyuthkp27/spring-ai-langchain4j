# Aegis (LangChain4j) — Customer Banking Assistant

Customer-facing self-service banking chatbot: **Spring Boot 3.5 + LangChain4j 1.17** backend
on **Ollama qwen3.5:4b**, with a **Next.js 15** animated chat frontend. Re-implementation of
the Spring AI `aegis-ai` project with the persona shifted from staff copilot to end customer.

## Stack

- **Chat**: qwen3.5:4b (think=false), streaming via LangChain4j `TokenStream` → SSE
- **RAG**: pgvector (768-dim nomic-embed-text), per-tenant markdown policy docs, tenant-filtered retrieval
- **Memory**: `MessageWindowChatMemory(20)` on Postgres, key `tenantId:userId:conversationId`
- **Guardrails** (all code-enforced, inline on the stream): injection screen, PII redaction at
  safe line boundaries, per-tenant rate limit + daily token budget, ScopeGate LLM classifier,
  semantic cache (all-minilm, 0.62 threshold), resilience4j circuit breaker + idle watchdog
- **Security**: HS256 JWT → `Principal(userId, tenantId, permissions)`; tools built PER REQUEST
  with identity in constructor fields — the model never sees or supplies identity; ownership
  checked inside every tool; money movement only creates `PENDING_HUMAN_APPROVAL`
- **MCP**: read-only policy search over SSE (`/sse`, `/mcp/message`)
- **Frontend**: Next.js 15 App Router, Tailwind v4, framer-motion; SSE-over-POST client;
  tool-progress chips, streaming caret, conversation sidebar, dark mode, mobile drawer

## Run

```bash
# 1. Infra (Postgres+pgvector on 5432; models must be pulled in Ollama)
docker compose up -d
ollama pull qwen3.5:4b nomic-embed-text all-minilm

# 2. Backend (port 8081 — 8080 is often held by the original aegis-ai)
cd backend && ./mvnw spring-boot:run

# 3. Ingest policy docs (admin JWT)
ADMIN=$(curl -s -X POST localhost:8081/api/auth/token -H 'Content-Type: application/json' -d '{"role":"admin"}' | jq -r .token)
curl -X POST localhost:8081/api/admin/ingest -H "Authorization: Bearer $ADMIN"

# 4. Frontend
cd frontend && npm run dev   # http://localhost:3000 (proxies /api → 8081)
```

## API

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/auth/token` | none (dev) | mint JWT; default demo customer |
| `POST /api/assistant` | JWT | streaming chat (SSE: `token`/`status`/`meta`) |
| `GET /api/assistant/history` | JWT | restore a conversation |
| `POST /api/rag/ask` / `ask-advanced` | JWT | grounded policy Q&A (advanced = query compression) |
| `POST /api/admin/ingest`, `/api/admin/analytics`, … | admin JWT | ops |
| `/sse` + `/mcp/message` | none (POC) | MCP server, read-only policy search |

## Tests

`cd backend && ./mvnw test` — deterministic guardrail + streaming-filter unit tests
(no Ollama needed).
