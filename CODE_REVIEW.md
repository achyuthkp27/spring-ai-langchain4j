# AegisAI — Production Readiness Code Review

Scope: `aegis-merged` (primary, 5.6k LoC Java), `aegis-lc4j` (backend + Next.js frontend),
`aegis-ai` (legacy base). Reviewed for correctness, authorization, validation, resilience,
data isolation, and operational safety.

Overall: the architecture is genuinely good — code-enforced authz in tools, human-in-the-loop
for money movement, a double-entry ledger, tenant-filtered retrieval, hash-chained audit. The
gaps are concentrated in **authentication**, **input validation on money-adjacent tools**, and
**guardrail coverage on paths that aren't the main streaming endpoint**. Several controls are
documented as production-hardened in comments but are not actually enforced in code.

---

## P0 — Ship blockers

### 1. `/api/auth/token` mints arbitrary identities with no authentication
`aegis-merged/.../security/AuthController.java:28-35`, `aegis-lc4j/.../security/AuthController.java:28`
and permitted in `SecurityConfig.java:37` / lc4j `SecurityConfig.java:32`.

Anyone who can reach the app POSTs `{"role":"admin","tenantId":"globex-bank"}` and receives a
signed token with `PERM_admin:all`. The whole authz model rests on a verified JWT, and the app
hands out any JWT you ask for. The frontend already does exactly this:
`aegis-lc4j/frontend/src/lib/adminApi.ts:20-24` mints its own admin token client-side — so
navigating to `/admin` is full admin access, including cross-tenant transcripts.

The Javadoc says "DEV-ONLY", but nothing enforces that.

```java
@RestController
@RequestMapping("/api/auth")
@Profile("!prod")                       // <- at minimum
public class AuthController {
    @PostMapping("/token")
    public Map<String, Object> token(@RequestBody(required = false) TokenRequest req) { ... }
}
```
Better: drop it entirely and validate tokens from a real issuer (`spring-boot-starter-oauth2-resource-server`,
`issuer-uri` + JWKS). Keep `AuthController` only in `src/test`. At the very least, refuse to
register the bean when `aegis.auth.dev-tokens=true` is absent, and never allow `role=admin`
from it.

### 2. LC4J ships a real JWT signing secret in the repo
`aegis-lc4j/backend/src/main/resources/application.yml:26`

```yaml
aegis:
  jwt:
    secret: change-me-in-prod-this-is-a-dev-only-256bit-secret!!
```

Anyone with the repo can forge a valid admin token for any deployed instance of this build.
`aegis-merged`'s `JwtService` at least detects its built-in default and refuses under `prod` —
but this one is a *configured* value, so that check would not fire even if it were ported.

Remove it from `application.yml`; require `AEGIS_JWT_SECRET` and fail startup if unset.

Related, `aegis-merged/.../security/JwtService.java:53`: the prod refusal runs on
`ApplicationReadyEvent`, i.e. *after* the connector is bound and accepting traffic. Move it to
the constructor or `@PostConstruct` so it fails before the port opens. And `usingDefaultSecret`
only catches the exact literal — a 12-character custom secret passes silently (jjwt will reject
<256-bit keys, but a short-but-valid weak passphrase won't be flagged).

### 3. MCP endpoints are unauthenticated and take `tenantId` from the caller
`SecurityConfig.java:42` (`/sse`, `/mcp/**` → `permitAll`) + `mcp/PolicyTools.java:29-31`

```java
@Tool(description = "Search a bank tenant's policy documents...")
public String searchPolicies(
        @ToolParam(description = "tenant id, e.g. achu-bank or globex-bank") String tenantId,
        @ToolParam(...) String query) {
```

No auth, and the tenant boundary is a parameter the caller chooses. Any unauthenticated client
on the network enumerates every tenant's policy corpus. The comment says "In production this
endpoint is protected with Spring Security OAuth2" — it isn't, in any profile.

Either put `/sse` and `/mcp/**` behind the same `authenticated()` rule and derive `tenantId`
from the principal, or bind the MCP transport to a separate internal-only connector.

### 4. `read-only` role can move money
`tools/BankingTools.java:462` requires `account:read` for `transferBetweenOwnAccounts`, and
`JwtService.permissionsFor` grants `read-only` → `{"account:read"}`.

Same for `updateContactInfo` (:633), `requestAccountClosure` (:673), `renameAccount` (:654),
`escalateCase` (:751), `addCaseEvidence` (:735), `setTravelNotice`, `setAlertPreferences`, and
`requestIdentityReVerification`. Every mutating tool except the card/dispute ones is gated on a
*read* permission, so the least-privileged role has write access to contact details (which is
the fraud-alert channel) and to balances.

Introduce real permissions and use them:

```java
case "customer"  -> Set.of("account:read", "account:write", "money:transfer",
                           "cases:create", "credit:request", "cards:manage", "profile:write");
case "read-only" -> Set.of("account:read");
```
…then `require(p, "money:transfer")`, `require(p, "profile:write")`, etc.

### 5. Negative transfer amounts reverse the direction of money movement
`domain/BankingService.java:236` + `tools/BankingTools.java:475`

```java
amt = new BigDecimal(amount);            // no sign / scale / cap check
...
if (from.balance().compareTo(amount) < 0) throw new IllegalStateException("Insufficient funds");
BigDecimal fromBalance = from.balance().subtract(amount);   // subtract(-100) => +100
```

`amount = "-100"` passes the funds check (2500 ≥ -100), credits the source, debits the
destination, and posts a ledger pair labelled `DEBIT`/`CREDIT` that contradicts the actual
movement. There is also no maximum, no scale check (`"0.00001"`), and no daily limit.

```java
if (amt.signum() <= 0) return "Transfer amount must be positive.";
if (amt.scale() > 2)   return "Amounts are limited to cents.";
if (amt.compareTo(MAX_SELF_TRANSFER) > 0)
    return "That exceeds the per-transfer limit of $" + MAX_SELF_TRANSFER + ".";
```
And enforce the same invariant defensively inside `BankingService.transfer` — the domain layer
should never accept a non-positive amount regardless of caller.

### 6. Cross-tenant filter injection in `/api/rag/ask`
`rag/RagController.java:48`

```java
.filterExpression("tenantId == '" + tenantId + "'")
```

This is the exact pattern `PolicyTools.java:33-35` and `PolicySearchTool.java:50-53` explicitly
warn against and avoid. `tenantId` is a JWT claim, but with finding #1 an attacker controls it,
and even with a real IdP this is a filter-injection sink. Use the same typed builder as
everywhere else:

```java
.filterExpression(new FilterExpressionBuilder().eq("tenantId", tenantId).build())
```

### 7. Admin API has no tenant scoping — an admin of one bank reads every bank
`admin/AdminController.java` — `/conversations` (:112), `/conversations/{id}/messages` (:134),
`/audit/query?tenant=` (:166), `/rag` (:149), `/aml/flags?tenant=` (:209), `/cache/clear?tenant=`
(:215); `guardrails/BudgetAdminController.java:33` (`set` takes `tenantId` from the body);
`admin/PrivacyController.java:44` (`erase` takes `tenantId` from a query param).

`hasAuthority("PERM_admin:all")` is global. An `achu-bank` admin can pull `globex-bank`'s full
conversation transcripts, audit rows, and AML flags — and erase their data. In a multi-tenant
banking platform that is the breach.

Every one of these should default to `CurrentUser.get().tenantId()` and reject a mismatch,
with a separate `platform:admin` permission for the genuinely cross-tenant operator view:

```java
private void requireTenantAccess(String requested) {
    var p = CurrentUser.get();
    if (!p.can("platform:admin") && !p.tenantId().equals(requested))
        throw new AccessDeniedException("Not your tenant.");
}
```
`transcript(@PathVariable String id)` additionally needs to verify the `tenant:user:` prefix of
the conversation id against the caller.

### 8. `/api/rag/**` bypasses every guardrail
`rag/RagConfig.java:32-38` builds `ragClient` with `tokenAudit` + memory only — no
`GuardrailAdvisor`. And neither `RagController.ask` nor `AdvancedRagController.ask` performs
the manual rate-limit / budget / injection / PII checks that `AssistantController.stream` does.

So an authenticated user drains the tenant token budget, bypasses the injection screen, and
sends unredacted PII to the model through `/api/rag/ask`. Add `guardrails` to the `ragClient`
advisor chain, and route these endpoints through the same input gate the streaming endpoint uses.

### 9. Chat memory in `/api/rag/ask-advanced` is shared across users in a tenant
`rag/AdvancedRagController.java:83`

```java
String memoryKey = tenantId + ":" + request.conversationId();   // no userId
```

`AssistantController:191` correctly uses `tenantId + ":" + userId + ":" + cid`. Here, two users
of the same bank both posting `conversationId: "default"` read each other's conversation
history. Same key shape, one missing segment.

---

## P1 — Correctness and data-integrity bugs

### 10. PII redaction destroys the prompt
`guardrails/GuardrailAdvisor.java:74-76`

```java
effective = request.mutate()
        .prompt(new Prompt(new UserMessage(redacted)))   // replaces ALL messages
        .build();
```

When redaction fires, the system prompt and the entire conversation history are discarded and
replaced with a single user message. The model loses its persona, its rules, and the thread —
precisely on the turns that contain sensitive data. Rebuild the message list, replacing only
the last `UserMessage`:

```java
var msgs = new ArrayList<>(request.prompt().getInstructions());
for (int i = msgs.size() - 1; i >= 0; i--) {
    if (msgs.get(i) instanceof UserMessage) { msgs.set(i, new UserMessage(redacted)); break; }
}
effective = request.mutate().prompt(new Prompt(msgs, request.prompt().getOptions())).build();
```

### 11. Suppressed tool-call leakage still reaches the client and the cache
`assistant/AssistantController.java:316` vs `:449`

`full.append(chunk)` accumulates the **raw** stream. `tokenEvent()` blanks a leaked tool-call
segment for the live token event, but `full` keeps it, and `full` is what gets PII-redacted,
written to `semanticCache.put` (:341) and emitted in the terminal `meta` event (:349). The
frontend's `onMeta` doesn't overwrite the text, so the live view is fine — but the cached
answer and the meta payload contain the JSON blob, and the cached version is served verbatim to
the next matching question. Apply the same sanitization to `full` before caching.

### 12. Widget history attaches to the wrong messages after ~10 turns
`assistant/AssistantController.java:248-251` + `config/CopilotConfig.java:22`

`turnSeq` is computed from `chatMemory.get(memoryKey)`, but `MessageWindowChatMemory` is capped
at `maxMessages(20)`. Once a conversation passes 20 messages the assistant count saturates
around 10, so every later turn gets roughly the same `turnSeq` and `GET /history` piles all
subsequent widgets onto one message.

`turnSeq` needs a monotonic source — a per-conversation counter column, or
`SELECT COALESCE(MAX(turn_seq),0)+1 FROM assistant_widget_event WHERE conversation_id = ?`.

### 13. Widget/status SSE events can be dropped, and can arrive after `meta`
`assistant/AssistantController.java:242-277`, `:401`

The sinks are `Sinks.many().multicast().onBackpressureBuffer()`, which drops anything emitted
before a subscriber attaches. `Flux.merge` subscribes to `answer` first (which kicks off the
model call), so an early tool emission can land before the sink is subscribed. Use
`Sinks.many().unicast().onBackpressureBuffer()` — it buffers pre-subscription.

Separately, `Flux.merge` gives no cross-source ordering, so a `cards` event can be delivered
*after* the terminal `meta` event. The frontend sets `streaming: false` on `meta` but still
applies later widget patches, so it currently survives — it's fragile. Consider emitting the
terminal `meta` only after all side channels complete.

### 14. `/api/assistant` throws before returning the Flux when Ollama is down
`assistant/AssistantController.java:216` — `semanticCache.lookup()` calls `embeddingModel.embed()`,
and `:224` `scopeGate.inScope()` makes a blocking classifier call. Neither is wrapped in
`llmGuard` or a try/catch, and both run on the request thread before the `Flux` is constructed.
An Ollama outage produces a raw 500 instead of the carefully-written "assistant is busy"
message that `onErrorResume` (:403) provides for every other failure. Wrap both; on failure,
skip the cache / fail the gate open and continue.

### 15. `guard(Flux)` kills long legitimate answers, and orders the operators inversely to `call()`
`guardrails/LlmGuard.java:110-115`

```java
return source
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .timeout(TIMEOUT)                                  // 30s for the WHOLE stream
        .transformDeferred(CircuitBreakerOperator.of(breaker));
```

`.timeout()` on a Flux is an *inter-element* timeout in Reactor — so this is actually a 30s gap
timeout, which is the right semantic, but it's not what the comment claims ("hard timeout").
Worth making explicit with `.timeout(FIRST_TOKEN_TIMEOUT, Duration.ofSeconds(10))` so
time-to-first-token and inter-token stalls get different budgets.

More importantly, the operator nesting is reversed relative to `call()` (:88-92, bulkhead
outermost). Here the circuit breaker is outermost and the bulkhead innermost, so bulkhead
rejections are recorded as circuit-breaker failures and can trip the breaker under pure
concurrency pressure. Make both paths order identically.

Also `TimeLimiter.decorateFutureSupplier` + `CompletableFuture.supplyAsync` (:89-90):
`cancelRunningFuture(true)` cancels the future but cannot interrupt the blocking HTTP call, so
the 16-thread pool can be fully occupied by calls that have already "timed out". And `pool` has
no `@PreDestroy` shutdown.

### 16. Streaming budget accounting under-counts by an order of magnitude
`assistant/AssistantController.java:346`

```java
budgetGuard.record(tenantId, Math.max(1, answer.length() / 4));
```

Only the *output* is counted. The system prompt (~700 tokens), conversation history (up to 20
messages), tool schemas for ~20 tools, and RAG chunks (topK 6, full text) are all prompt tokens
and none are recorded. A tenant's real spend can be 10–20× the recorded figure, so
`BudgetGuard` never fires. Ollama returns `prompt_eval_count` on the final chunk —
`TokenAuditAdvisor.adviseStream` (:59-92) already captures it for metrics; feed the same value
into `budgetGuard.record` and drop the char heuristic.

### 17. `checkOrThrow` / `record` is not atomic
`guardrails/BudgetGuard.java:80-93`. Concurrent requests all pass the pre-check before any
records land, so a tenant can overshoot by `concurrency × tokens_per_call`. Acceptable for a
soft quota; document it, or make the Lua script do check-and-increment in one round trip.

Also `budgetFor` (:74) does an unguarded `Long.parseLong` on a Redis value, and every Redis call
propagates on failure — a Redis blip fails *closed* on the budget path and takes the whole app
down. Decide the policy explicitly and catch.

### 18. Ingestion deletes the entire corpus before re-adding it, non-transactionally
`rag/IngestionService.java:47-56`

```java
var all = vectorStore.similaritySearch(SearchRequest.builder()
        .query("*").topK(10_000).similarityThreshold(0.0).build());
vectorStore.delete(all.stream().map(Document::getId).toList());
```

Three problems: (a) it deletes **all tenants'** chunks, including anything added via
`IngestionService.add`; (b) `topK(10_000)` silently leaves residue above that bound, so
"idempotent" stops being true as the corpus grows; (c) if the subsequent `vectorStore.add`
throws, the corpus is gone with no rollback and the assistant answers every policy question
with "No matching policy passages found."

Delete by metadata filter per tenant, inside a transaction, or write to a new namespace and
swap. `similaritySearch("*")` also burns an embedding call to do a table scan.

### 19. Retrieved document text is injected into the prompt unscreened
`assistant/PolicySearchTool.java:78-80` returns raw chunk text; `InjectionScreen` only ever sees
the user's message (`GuardrailAdvisor.java:63`, `AssistantController.java:209`). Indirect prompt
injection through an ingested policy document is completely unmitigated, which is the more
realistic LLM01 vector in a RAG system. Run retrieved text through `injection.screen()` too, and
delimit it explicitly (e.g. wrap in `<document>` tags with an instruction that document content
is data, never instructions).

Also: `topK(6)` × full chunk text with no length cap goes straight into the context on every
policy question.

### 20. GDPR erasure misses the semantic cache and the vector store
`admin/PrivacyController.java:44-54` clears `assistant_audit_event`, `assistant_widget_event`,
`spring_ai_chat_memory` and contact info — but a cached answer derived from the user's question
survives in `SemanticCache` (in-memory, tenant-scoped, 30-minute TTL) and any adhoc-ingested
document survives in `vector_store`. Add `semanticCache.clear(tenantId)` at minimum.

### 21. `verifyChain()` loads the entire audit table into memory
`admin/AuditTrail.java:178-186`. Unbounded `jdbc.query` over `assistant_audit_event ORDER BY id`
with no `LIMIT` — an OOM waiting for the table to grow. Stream with a `RowCallbackHandler` and
a fetch size, or verify a bounded window.

The chain itself is also weaker than the Javadoc implies: it's an unkeyed SHA-256, so anyone
with DB write access recomputes the whole chain after editing. Use an HMAC with a key the DB
doesn't hold. And `lastHash` is per-instance (`:78`) — with two replicas, the two chains
interleave and `verifyChain` reports tampering on a healthy system.

---

## P2 — Validation gaps

Every one of these is a raw model-supplied string reaching a domain call with no check:

| Location | Missing validation |
|---|---|
| `BankingTools.java:440` `issueProvisionalCredit` | `new BigDecimal(amount)` unguarded → `NumberFormatException` escapes the tool. No positivity check, no cap, no check against the disputed transaction's amount. Nothing stops a $1,000,000 credit request on a $49.99 dispute. |
| `BankingTools.java:588` `setCardSpendingLimit` | `new BigDecimal(limit)` unguarded; negative limits accepted. |
| `BankingTools.java:624-644` `updateContactInfo` | No email or phone format validation. This is the fraud-alert channel and the account-takeover path — it should require step-up auth, not a chat confirmation. |
| `BankingTools.java:601` `toggleMerchantCategoryBlock` | `category` is arbitrary free text stored uppercased; no enum. |
| `BankingTools.java:652` `renameAccount` | `nickname` unbounded length, no charset restriction. |
| `BankingTools.java:710` `setTravelNotice` | Date parsed but not bounded — accepts `0001-01-01` and `+9999-12-31`. |
| `AssistantController.java:150` `ChatRequest` | **`message` has no length limit at all** and `conversationId` is unbounded and user-controlled while forming a DB key (`varchar(256)` after `ChatMemorySchemaFix`) — a long id silently fails the memory insert. Add `@Valid` + `@Size(max = 4000)` / `@Pattern` and a `@ControllerAdvice` returning 400. |
| `AdminController.java:112` | `limit` uncapped on `/conversations` (other endpoints cap correctly). |
| `AdminController.java:171-172` | `Instant.parse(from)` throws → 500 instead of 400. |
| `BudgetAdminController.java:33` | `tenantId` may be null (→ key `...:null`); negative budgets accepted. |

Also `Approval` (`BankingService.java:46`) has **no `tenantId` field** — so any admin surface
that lists approvals cannot filter by tenant even if it wanted to. Add it.

And `requestAccountClosure` (`BankingTools.java:686`) passes `acct.balance()` as the approval
*amount*, overloading a field that means "money to move" with "balance at request time".

---

## P3 — The `confirmed` flag is not actually a confirmation

Every mutating tool takes `Boolean confirmed` and the class Javadoc claims "excessive agency is
structurally impossible" (`BankingTools.java:22-23`). But `confirmed` is a parameter the **model**
fills in. Nothing in code correlates it with an actual user utterance. A prompt injection in a
merchant name, a policy document, or a prior message can make the model pass `confirmed=true`
on the first call, and every check passes.

The human-approval gate for money movement is real and good. The confirmation gate is prompt-
enforced only. To make it structural:

```java
// first call: mint a short-lived, server-side pending-action token bound to
// (userId, tool, canonical args hash); return it in the CONFIRMATION_REQUIRED message.
// second call: require confirmationToken, look it up, verify args hash matches, single-use.
```
That way the model cannot fabricate a confirmation, and re-confirmation can't be replayed with
different arguments.

---

## P4 — Operational / observability

1. **Prompts and completions are logged at DEBUG in the default profile.**
   `application.yml:123-127` sets `org.springframework.ai.chat.client.advisor: DEBUG`, with a
   comment saying it "is a data-leak vector in production" — and the `prod` profile (:192-199)
   never overrides it back. Set `INFO` by default and `DEBUG` only in a `dev` profile.

2. **Raw, unredacted user input in logs.** `ScopeGate.java:162` (`msg='{}'`),
   `SemanticCache.java:106` (`matched='{}'`) and `:116` (`q='{}'`) all log the user's question
   verbatim at INFO. The audit trail is careful to store only redacted text; the logs aren't.
   Redact before logging, or drop the message from these lines.

3. **`/actuator/prometheus` is `permitAll`** (`SecurityConfig.java:38`) and the token counters are
   tagged by tenant (`TokenAuditAdvisor.java:48`) — anyone can enumerate your tenant list and
   their spend. Move it behind auth or to a management port.

4. **Unbounded metric/map cardinality on tenant.** `tenant` is a JWT claim, so with finding #1 an
   attacker creates unlimited Micrometer counter series (`TokenAuditAdvisor.java:48-51`) and
   unlimited entries in `AuditTrail.bySource` / `toolCalls`, `RateLimiter.buckets`,
   `BudgetGuard.windows`, `SemanticCache.byTenant`. Validate the tenant claim against a known
   set at token-parse time.

5. **`ModelRouter.escalated` never evicts.** `assistant/ModelRouter.java:48` — entries expire
   logically but are only removed when that exact key is looked up again (:58). Abandoned
   conversations leak forever. Use Caffeine with `expireAfterWrite`, same for `ScopeGate.verdicts`
   (whose `clear()`-when-full at :193 also throws away every warm verdict at once).

6. **Unbounded fire-and-forget queues.** `AuditTrail.persistExecutor` (:65) and
   `WidgetHistoryStore.persistExecutor` (:42) are single-thread executors with unbounded queues.
   A slow Postgres turns into unbounded heap growth. Use a bounded queue with a
   `CallerRunsPolicy` or discard-and-count. Neither `shutdown()` awaits termination, so in-flight
   audit rows are lost on restart despite `shutdown: graceful`.

7. **`AccessDeniedException` from a tool surfaces as "That request took too long."** Tool
   exceptions propagate out of the streaming pipeline into `onErrorResume`
   (`AssistantController.java:403`), which only distinguishes circuit/bulkhead errors. An authz
   denial should be caught in the tool and returned as a message the model can relay, or mapped
   to a distinct user-facing string.

8. **No CORS configuration** anywhere, despite a separate-origin frontend. It works today only
   because everything is same-origin through the Next.js proxy. Any direct-browser deployment
   breaks silently.

9. **No request size limits, no `spring.mvc.async.request-timeout`,** no `server.tomcat.max-swallow-size`.

10. **Rate limit is per-tenant, not per-user**, at 20 burst / 1 rps (`RateLimiter.java:24-25`).
    One user of a bank rate-limits every other user of that bank. For a "multi-tenant banking
    platform" that's a self-inflicted DoS. Key on `tenantId:userId` and give the tenant a
    separate, much higher ceiling.

11. **Redis rate-limit script uses client-side `System.currentTimeMillis()`** (`RateLimiter.java:77`).
    Clock skew between instances makes `elapsed` negative and drains buckets. Use `redis.call('TIME')`
    inside the script. The script also errors if the `tokens` field exists but `last` doesn't.

12. **`compose.yaml` has no volumes** — Postgres data (including the audit table) is lost when the
    container is removed. Plaintext `aegis/aegis` credentials, no Redis auth, no resource limits,
    no restart policy.

---

## P5 — Frontend

`aegis-lc4j/frontend`

1. **`stop()` leaves the UI permanently busy.** `useChatStream.ts:185` aborts the controller;
   `sse.ts:189` swallows `AbortError` without calling `onError`, so `busy` is never reset and the
   composer stays disabled. Reset state in the abort path.

2. **No token refresh on 401.** `api.ts:54` hardcodes `exp: Date.now() + 55*60_000`, decoupled
   from the server's `aegis.jwt.ttl-seconds`. If the server TTL is shortened, `getToken()` keeps
   returning a dead token and every request fails until the user clears sessionStorage. Add a
   401 → re-mint → retry-once wrapper, and derive `exp` from the token's own `exp` claim.

3. **`send()` has no try/catch around `await getToken()`** (`useChatStream.ts:140`) — an auth
   failure leaves a permanent empty streaming bubble.

4. **Conversation list is not scoped to the identity.** `useConversations.ts:11` uses a single
   global `localStorage` key. `switchIdentity` (`api.ts:88-92`) reloads the page but never clears
   it, so after switching to `globex-user` the sidebar still shows `demo-user`'s conversation
   titles — which are their message text. Key the store by `${tenantId}:${userId}`.

5. **"Delete chat" is cosmetic.** `useConversations.remove` (:62) only edits localStorage;
   `spring_ai_chat_memory` and `assistant_widget_event` rows persist server-side forever. There's
   no delete endpoint at all.

6. **Tokens in `sessionStorage`** are readable by any XSS. Acceptable for a demo; for a banking
   product, use an httpOnly, `SameSite=Strict` cookie with CSRF protection re-enabled.

7. **Proxy path traversal.** `app/api/[...path]/route.ts` interpolates `path.join("/")` into
   `${base}/api/${path}` unvalidated; a `..` segment reaches non-`/api` backend paths. Validate
   segments against `^[A-Za-z0-9._-]+$`. The proxy also drops all response headers except
   `content-type` (losing `WWW-Authenticate`, `Retry-After`) and has no timeout on `forward`, so a
   hung backend pins a Next.js worker. And the localhost port-probing fallback should not exist
   when `BACKEND_URL` is unset in production — it should fail loudly.

8. Markdown rendering is **safe** — `react-markdown` v10 without `rehype-raw` escapes HTML and
   sanitizes `javascript:` URLs. No change needed, just noting it was checked.

9. No `lint` script, no ESLint config, and no frontend tests at all.

---

## P6 — Testing

42 `@Test` methods across ~5.6k lines, 8 of them gated off by default
(`@EnabledIfEnvironmentVariable` on `RUN_CONTAINER_TESTS` / `RUN_EVAL_TESTS`). Nothing covers:

- Security wiring — no `MockMvc`/`@WebMvcTest` asserting that `/api/admin/**` 403s for a customer
  token, that `/api/assistant` 401s without one, or that a forged token is rejected.
- Tenant isolation — no test that tenant A's token cannot retrieve tenant B's documents,
  conversations, or audit rows. This is the system's central claim and it is untested.
- `AssistantController.stream` — the most complex method in the codebase (200 lines, 11 sinks,
  manual guardrails) has zero tests.
- Amount validation on transfers/credits (findings #5, P2).
- `AuditTrail.verifyChain` against a tampered row.
- `PiiRedactor` edge cases — the PAN regex `\b(?:\d[ -]?){13,19}\b` has no Luhn check and will
  redact any 13–19 digit run, including reference numbers and long amounts.

Given the domain, the tenant-isolation and authz tests are the ones to write first.

---

## Repo hygiene

- `aegis-merged/target/**` (compiled `.class` files, `application.yml`) and
  `aegis-lc4j/frontend/.next/**` are tracked in git. Add `.gitignore` entries and
  `git rm -r --cached` them.
- Three near-identical applications (`aegis-ai` 3.3k LoC, `aegis-merged` 5.6k, `aegis-lc4j` 2.8k)
  with copy-pasted `JwtService`, `SecurityConfig`, `BankingTools`, `PiiRedactor`, `RateLimiter`.
  Fixes have to be applied three times and have already drifted (`aegis-merged` tightened
  ownership checks in `BankingTools` that `aegis-ai` doesn't have). If `aegis-merged` is the
  direction, delete `aegis-ai` or move it to a tag.

---

## Suggested order of work

1. #1, #2, #3 — authentication. Nothing else matters until a token means something.
2. #4, #5, and the P2 amount validations — money-adjacent authz and input checks.
3. #6, #7, #9 — tenant isolation (filter injection, admin scoping, memory key).
4. #8, #10, #11 — guardrail coverage and the redaction/leak bugs.
5. Tenant-isolation + authz tests (P6), so 2–4 stay fixed.
6. #12–#21 and the P4 operational items.
