# aegis-merged — Best-of-both banking assistant (port 8082)

Spring AI chassis (advisor chain, autoconfigured pgvector/JDBC memory, MCP server)
with the LangChain4j build's improvements backported:

- **Per-user ownership checks** in every tool (`ownedAccount`: tenant AND ownerUserId),
  plus `listMyAccounts` and an ownership check on provisional credit.
- **Customer persona** (system prompt, ScopeGate classifier, `customer` JWT role,
  demo-customer default token).
- **ThinkTagFilter** on the stream (qwen3.5 `<think>` spans can never leak) on top of
  `aegis.llm.think=false`.
- **Redact-before-cache/meta**: the full answer is PII-redacted before it enters the
  semantic cache or the terminal meta event.
- Model: **qwen3.5:4b**.

## Run

```bash
# Shares the aegis-lc4j Postgres (spring-boot-docker-compose disabled):
docker compose -f ../aegis-lc4j/compose.yaml up -d
./mvnw spring-boot:run     # port 8082

ADMIN=$(curl -s -X POST localhost:8082/api/auth/token -H 'Content-Type: application/json' -d '{"role":"admin"}' | jq -r .token)
curl -X POST localhost:8082/api/admin/ingest -H "Authorization: Bearer $ADMIN"
```

## Frontend

The shared UI lives in `../aegis-lc4j/frontend` (`npm run dev`, port 3000). Its proxy
(`src/app/api/[...path]/route.ts`) auto-routes to whichever backend is up:
**8082 (this) → 8081 (aegis-lc4j) → 8080 (aegis-ai)**, re-probing every 10s and on
failure. Override with `BACKEND_URL=http://host:port npm run dev`.

Same API contract as both siblings: `/api/auth/token`, `/api/assistant` (SSE
token/status/meta), `/api/assistant/history`, `/api/rag/ask[-advanced]`, `/api/admin/**`,
MCP at `/sse`.
