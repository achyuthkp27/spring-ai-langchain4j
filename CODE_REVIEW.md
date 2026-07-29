# AegisAI — Code Review

**Scope:** `aegis-merged` (production backend, ~4.6k LoC main + 1.6k test) +
`aegis-lc4j/frontend` (shared Next.js UI, ~2.7k LoC). `aegis-ai` and `aegis-lc4j/backend` are
study builds — excluded, and the README now says so.

**Build state:** `mvn -o test` green, `tsc --noEmit` clean, `npm run lint` clean.

**Headline:** every item on the previous pass's open list is closed, verified in the current
source — Flyway landed, truncated answers are no longer cached, `mutated` is wired into the
cache decision, `ScopeGate` runs under `LlmGuard`, widgets survive a cancel, and the whole
§5 UI cluster (memoization, scroll behaviour, `aria-live`, delete confirmation) is fixed.

What this pass found is a different set: **one regression introduced by the widget-on-cancel
fix**, **a validation path that returns the wrong status code** (verified empirically, not by
inspection), and **three infrastructure/proxy defects that only bite outside the happy path** —
a 30-second cap on every streamed turn, a failover path that can never work, and a Redis
configuration that cannot authenticate against the Redis this repo ships.

---

## 1. Verified closed this pass

Re-checked in the current source, not carried over from notes.

**Schema management (was the last architectural gap)**
- `flyway-core` + `flyway-database-postgresql` in `pom.xml`; all DDL in
  `src/main/resources/db/migration/V1__init.sql`.
- `WidgetHistoryStore.createSchema`, `AuditTrail.createSchema` and `ChatMemorySchemaFix` are all
  gone; `spring.ai.chat.memory.repository.jdbc.initialize-schema: never`.
- Migration failure now fails startup instead of a swallowed `log.warn`.

**Correctness items**
- `AssistantController:304-314` — `boolean truncated` gates both the cache write
  (`!truncated && isSafeToCache(...)`) and `router.markLowConfidence(..., toolFailed || truncated)`.
  A `finish_reason: length` answer is no longer cached.
- `mutated` is read at `:311` and folded into the cache-safety signal
  (`isSafeToCache(answer, dynamic || mutated)`) — the 17 `markMutated` call sites now do something.
- `ScopeGate:127` — the classifier call is wrapped in `llmGuard.call(...)`, so it sheds load
  through the same circuit breaker/bulkhead/time limiter as every other model call.
- `AssistantController:365-370` — widgets are persisted on the `CANCEL` branch of `doFinally`,
  so a stopped turn no longer silently drops its receipts. (See §2.1 for the side effect.)
- `BudgetGuard` — `checkOrThrow` is a single atomic Lua script, `Long.parseLong` is inside the
  guarded block, and Redis failures fail **open** with a logged warning.
- `JwtService:55-60` — a blank `aegis.known-tenants` now refuses to start under `prod` instead of
  silently disabling the allowlist.
- `BankingService.findTransaction` is an O(1) `txnIndex` lookup, not a full scan.
- Every `computeIfPresent` caller (`freezeCard`, `unfreezeCard`, `setSpendingLimit`,
  `toggleMerchantCategory`, `renameAccount`, `addEvidence`, `escalateCase`) null-checks the result
  before `emitCards`/`emitCase` — the `List.of(null)` NPE is unreachable.
- `requestAccountClosure` passes `BigDecimal.ZERO`, not the balance, into `Approval.amount`.
- `GuardrailAdvisor.looksLikeLeakedToolCall` is a structural regex
  (`{"name":"…","parameters":{`) instead of the four-substring heuristic — legitimate answers that
  quote JSON survive.
- `AmlMonitor` thresholds are `@Value`-driven, and `VELOCITY` excludes self-transfers.

**Frontend**
- Every chat component is `memo(...)` — `MessageBubble`, `AccountCards`, `TransactionList`,
  `CardCarousel`, `CaseStatusCard`, `ApprovalCard`, `CitationChips`, `LedgerReceipt`,
  `SpendingStatement`, `ProfileCard`. `patchBot` preserves identity for untouched messages, so
  referential equality actually holds and a streamed token no longer re-parses every message's
  markdown.
- `ChatShell:59` — `behavior: busy ? "auto" : "smooth"`. No more restarted smooth-scroll per token.
- `ChatShell:149-151` — a dedicated `sr-only` `aria-live="polite"` element announcing
  "Assistant is responding…" while streaming and the completed text once, with `aria-live="off"`
  on the bubble itself (`MessageBubble:41`).
- `Sidebar:176` — delete goes through `window.confirm`.
- The proxy no longer forwards a client-supplied `x-forwarded-for` (`route.ts:52` allow-lists
  `authorization`, `content-type`, `accept` only).
- Root `README.md` marks `aegis-merged` + `aegis-lc4j/frontend` active and the other two trees
  study-only.

---

## 2. Open — P1

### 2.1 A cancelled turn permanently misaligns widget history for that conversation

`AssistantController.java:366` (new) and `:118-131` (existing)

Fixing the dropped-widgets bug introduced a counter-drift bug. `history()` aligns widgets to
messages arithmetically:

```java
int assistantSeq = widgetHistoryStore.currentTurnSeq(memoryKey) - (int) assistantCountInWindow;
```

That is only correct while `turn_seq` advances **exactly once per persisted assistant message**.
The cancel path now calls `nextTurnSeq` for a turn whose assistant message
`MessageChatMemoryAdvisor` never persists — it aggregates and writes on stream *completion*, and
a cancel has no completion. So `currentTurnSeq` runs one ahead of the assistant-message count
forever after.

Concretely:

| | `turn_seq` | assistant msgs | `history()` maps msg #1 to |
|---|---|---|---|
| turn 1 completes | 1 | 1 | seq 1 ✓ |
| turn 2 cancelled, cards emitted | 2 | 1 | seq 2 ✗ — the cancelled turn's cards |

Every subsequent reload shows the wrong turn's cards, accounts and approval receipts against the
wrong message, and the drift is permanent (persisted in `assistant_turn_counter`) and compounds
with each cancel.

**To do:** stop deriving alignment by arithmetic. Persist the turn seq alongside the assistant
message, or write a tombstone row for the cancelled turn so the counts stay in step. A cheaper
stopgap: on cancel, save the widgets under `currentTurnSeq(key) + 1` **without** incrementing the
counter, so the next completed turn reclaims that seq — the cancelled turn's widgets then attach
to the retry, which is closer to what the user expects anyway.

### 2.2 Request validation returns 500 with a generic message, not 400 with the reason

`AssistantController.java:160-177`, `ApiExceptionHandler.java:31-47`

`ChatRequest`'s compact constructor throws `IllegalArgumentException` for a blank message, an
over-4000-char message, or a bad `conversationId`. Because it throws during Jackson
deserialization, Spring wraps it in `HttpMessageNotReadableException` before the advice sees it —
and `@ExceptionHandler(Exception.class)` matches that wrapper directly, so
`ExceptionHandlerMethodResolver` never walks down to the `IllegalArgumentException` cause.

Verified, not inferred — running the real resolver against
`new HttpMessageNotReadableException(msg, new IllegalArgumentException(...), null)` resolves to
`onUnexpected`. The caller gets `500 {"error":"Something went wrong. Please try again."}` and a
stack trace at `ERROR` in the logs for what is an ordinary client mistake.

`ApiExceptionHandlerTest` passes because it invokes the handler methods directly and never
exercises resolution.

**To do:** add an explicit `@ExceptionHandler(HttpMessageNotReadableException.class)` that
unwraps `IllegalArgumentException` from the cause chain and returns 400 with its message,
falling back to a generic "Malformed request body." Add a `MockMvc` test that POSTs a 5000-char
message and asserts 400 — the current unit test cannot catch this class of bug.

Same root cause, smaller blast radius: `MethodArgumentTypeMismatchException` (e.g.
`/api/admin/events?limit=abc`) also lands on the catch-all as a 500. `DateTimeParseException`
from `auditQuery` is fine — it's thrown directly, so depth-based matching picks `onBadInput`.

### 2.3 The Next.js proxy caps every streamed turn at 30 seconds

`frontend/src/app/api/[...path]/route.ts:62`

```ts
signal: AbortSignal.timeout(FORWARD_TIMEOUT_MS),   // 30_000
```

That signal aborts the fetch *and its response body stream*, not just the headers. All SSE goes
through this route (`sse.ts:113` posts to `/api/assistant`), so any turn whose total wall-clock
exceeds 30s — a tool call plus a long generation is routinely more — is cut mid-stream and
surfaces to the user as `onError`, "Something went wrong reaching the assistant."

The backend is deliberately more generous: `spring.mvc.async.request-timeout: 60000`, and
`LlmGuard.guard` bounds only *first-token* (15s) and *inter-token* (30s) latency, not total
duration. The proxy is now the binding constraint and it is the one nobody tuned.

**To do:** don't apply a whole-request deadline to a streaming route. Either exempt
`text/event-stream` responses from the timeout, or replace it with a connect/first-byte deadline
(`AbortSignal.timeout` on the probe only) and let the backend's inter-token timeout police the
rest.

### 2.4 The proxy's failover retry replays an already-consumed request body

`frontend/src/app/api/[...path]/route.ts:90-98`

```ts
try {
  return await forward(req, base, joined);
} catch {
  base = await liveBackend(true);
  ...
  return forward(req, base, joined);       // req.body was consumed by the first forward()
}
```

`forward` passes `req.body` — a `ReadableStream` — straight into `fetch` with `duplex: "half"`.
Once the first attempt has read it, the stream is locked/disturbed and the retry throws
`TypeError: body used already`, which escapes `handle` entirely and becomes an unhandled 500.

So the failover path works only for `GET`/`DELETE` and is actively harmful for every mutating
call — including `POST /api/assistant`, which is the one request in the app that most wants a
retry.

**To do:** buffer the body once (`const body = await req.arrayBuffer()` for non-GET/HEAD) before
the first attempt and reuse the buffer, or drop the retry for methods with a body and let the
client handle it. Note that buffering also silently defeats streaming *uploads*, which this app
doesn't do — so buffering is the right trade here.

### 2.5 Redis cannot authenticate against the Redis this repo ships

`config/RedisConfig.java:16-20` vs `compose.yaml:4`

```java
return new LettuceConnectionFactory(host, port);   // spring.data.redis.password never read
```

`compose.yaml` starts Redis with `--requirepass ${REDIS_PASSWORD:-aegis-dev-only}`.
`AegisMergedApplication` excludes `RedisAutoConfiguration`, so this hand-rolled factory is the
only wiring and there is no fallback that would pick the password up.

Setting `aegis.redis.enabled=true` — the documented path to making rate limits, budgets and
confirmation tokens survive horizontal scaling — therefore fails `NOAUTH` on every Redis command.
And because `RateLimiter.allowRedis` (`:99-103`) has no try/catch, that exception propagates out
of `AssistantController:201` and every chat turn 500s. The feature is unusable as shipped, and it
fails in the worst possible direction.

**To do:** read `spring.data.redis.password` (and ideally `username`/`ssl`) into a
`RedisStandaloneConfiguration`, and add the `@Value` to `RedisConfig`. Then fix §3.1 so a Redis
outage degrades instead of denying.

---

## 3. Open — P2

### 3.1 `RateLimiter` still fails closed on Redis trouble

`guardrails/RateLimiter.java:99-103` is the last Redis caller with no exception handling —
`BudgetGuard` and `ConfirmationGuard` both got their failure policy decided explicitly this pass.
A Redis blip takes down every chat turn with a 500 rather than degrading to the local Caffeine
buckets that are already sitting right there in `allowLocal`.

**To do:** catch, log, and fall through to `allowLocal`. Per-instance limits under a Redis outage
are strictly better than no service.

### 3.2 `verifyChain` does not anchor the chain to GENESIS

`admin/AuditTrail.java:216-218`

```java
if (expectedPrev.get() == null) {
    expectedPrev.set(recordedPrev);      // trusts whatever the first surviving row claims
}
```

The first row's `prev_hash` is accepted on faith, so **deleting rows from the head of the table is
undetectable** — the chain re-anchors to whatever is left and validates clean. Tail truncation is
inherently undetectable in a hash chain; head truncation is not, and it is the cheaper attack.

**To do:** assert the first non-legacy row's `prev_hash` equals `"GENESIS"`, and report a distinct
verdict (`chainHeadMissing`) when it doesn't, so `PrivacyController`'s documented, intentional
divergence stays distinguishable from a deletion.

### 3.3 Two token-usage sites unbox a possibly-null `Integer`

- `guardrails/GuardrailAdvisor.java:87` — `budget.record(tenant, cr.getMetadata().getUsage().getTotalTokens())`
- `guardrails/TokenAuditAdvisor.java:44` — `.increment(usage.getTotalTokens())`

Both null-check `getUsage()` but not `getTotalTokens()`, which is a boxed `Integer` and is null
for providers that omit usage. The corresponding **streaming** paths get this right
(`TokenAuditAdvisor:64` and `AssistantController:445` both check `getTotalTokens() != null`), which
is what makes these two look like oversights rather than a decision.

**To do:** hoist the same null check. One line each.

### 3.4 The synchronous embedding call is now the last unguarded model hop on the request thread

`AssistantController.java:220` → `SemanticCache.lookup` → `embeddingModel.embed(question)` (`:72`).

This is exactly the problem §2.5 of the last pass just fixed for `ScopeGate`: a model round-trip on
the Tomcat request thread, before the reactive pipeline exists, outside the circuit breaker,
bulkhead and time limiter. The controller catches `Exception` so it can't error the request, but a
hung Ollama embedding endpoint holds the thread for its full socket timeout.

`SemanticCache.put` (`:101`) has the same call on the reactive terminal, alongside
`widgetHistoryStore.nextTurnSeq` (`AssistantController:316`) — a blocking JDBC round-trip on a
Reactor thread.

**To do:** wrap both `embed` calls in `llmGuard.call(...)`. For the terminal block, either
`subscribeOn(Schedulers.boundedElastic())` or move the persistence work off the signal thread.

### 3.5 Any tenant admin can re-ingest every tenant's documents

`rag/IngestionController.java:19-23` is under `/api/admin/**`, which requires `PERM_admin:all` —
held by the plain `admin` role, not just `platform-admin`. `ingestAll()` calls
`semanticCache.clear()` (all tenants) and then deletes and re-adds chunks for every tenant
directory on the classpath. Every other cross-tenant surface in `AdminController` goes through
`resolveTenantFilter`, which requires `platform:admin`; this one doesn't.

**To do:** `hasAuthority("PERM_platform:admin")` on `/api/admin/ingest`, or a per-tenant
`ingest(tenantId)` gated by `CurrentUser.requireTenantAccess`.

### 3.6 `getSpendingSummary` adds credits and debits into the same category total

`tools/BankingTools.java:564-569`

```java
byCategory.merge(category, t.amount(), BigDecimal::add);   // direction ignored
if ("CREDIT".equals(t.direction())) credits = credits.add(t.amount());
else debits = debits.add(t.amount());
```

`totalDebits`/`totalCredits` respect direction; `byCategory` doesn't. A $400 ATM withdrawal and a
$400 refund in the same category read as **$800 of spending** — in the widget
(`SpendingStatement`) and in the text handed to the model, which will then state it. `Transfers`
is the worst case, since `BankingService.transfer` writes both a "Transfer to" debit and a
"Transfer from" credit for a single self-transfer.

**To do:** either sum only debits into `byCategory`, or key it by `(category, direction)`.

### 3.7 The RAG endpoints skip the input validation the chat endpoint has

`rag/RagController.java:30-36` and `rag/AdvancedRagController.java:39-45` default a blank
`conversationId` and validate nothing else — no length cap, no character pattern, no cap on
`question`. `ChatRequest` has all three.

Two consequences: an authenticated user can post an arbitrarily large `question` into the model
(rate-limited by the advisor, but not size-limited — `multipart.max-request-size` doesn't apply to
a JSON body); and a `conversationId` over ~190 chars overflows
`spring_ai_chat_memory.conversation_id VARCHAR(256)` once the `tenant:user:` prefix is added,
producing a 500 from the driver.

**To do:** lift `ChatRequest`'s compact-constructor validation into a shared record or a small
static validator and apply it to both `AskRequest`s.

### 3.8 Smaller items

| Item | Location | Note |
|---|---|---|
| Widgets can be persisted twice | `AssistantController:316` vs `:365` | If the client cancels after the terminal `Mono.defer` saves but before completion, `doFinally(CANCEL)` sees a still-non-empty `pendingWidgets` and saves them again under a second `turn_seq`. Clear the list after the first save |
| `ConfirmationGuard` paths disagree on arg mismatch | `ConfirmationGuard:78` vs `:86` | The Redis `CONSUME` script deletes the token *before* comparing args, so a mismatch burns it; the in-memory path leaves it usable. Redis's behaviour is the safer one — make in-memory match |
| `renameAccount` races `transfer` | `BankingService:104` vs `:202` | `transfer` is `synchronized` and does read-modify-`put`; `renameAccount` uses `computeIfPresent` without that lock, so a concurrent rename is silently overwritten by the transfer's `put`. Money is safe, the nickname isn't |
| Card controls mutate without confirmation | `BankingTools:622`, `:659` | `setCardSpendingLimit` and `toggleMerchantCategoryBlock` are the only mutating tools with no `confirmationToken`, though raising a spending limit is a fraud-relevant change |
| Delete failure is swallowed | `useConversations.ts:76` | `void deleteConversation(convId).catch(() => {})` — the conversation disappears from the sidebar whether or not the server accepted the DELETE. It reappears on the next device |
| `pgvector.initialize-schema: true` | `application.yml:76` | Schema management is now split: Flyway owns three tables, Spring AI still creates `vector_store` at boot. Move it into `V1__init.sql` and set this to `false` so there is one owner |
| `PrivacyController.erase` accepts a blank tenant | `PrivacyController:33-35` | `requireTenantAccess("")` returns the caller's own tenant without throwing, then the SQL runs with the blank string and matches nothing. Reject blank explicitly |

---

## 4. Deliberate trade-offs — document, don't fix

Unchanged from the last pass and still correctly characterised:

- **§3.1 Confirmation tokens bind arguments, not consent.** The token still round-trips through
  the model, not the client, so the model can echo it back within one turn. `BankingTools`' class
  Javadoc should say *argument binding*. Real consent needs a dedicated SSE `confirm` event and an
  explicit Approve/Cancel control that POSTs the token back.
- **§3.2 `PiiRedactor`'s Luhn gate fails open on malformed PANs**, mitigated by `CARD_CONTEXT`
  (`PiiRedactor:46-50`) within a 40-char window. Names and addresses remain uncovered.
- **§3.3 `BankingService` is in-memory.** Balances, cards, disputes and the ledger are lost on
  restart and unshared between replicas. This is the seam a core banking platform plugs into. Note
  the asymmetry: the audit trail around the ledger *is* durable and HMAC-chained.
- **§3.4 `updateContactInfo` has no step-up auth.** Contact info is the fraud-alert channel;
  changing it is the classic ATO step and the only gate is a model-held token.
- **§3.5 The `AuditTrail` HMAC key is application-held**, so an app-level compromise forges the
  chain. Genuine tamper-evidence needs WORM storage or an external notary.
- **Tokens in `sessionStorage`** (`api.ts:25`) — readable by any XSS. Still the one frontend item
  with real security weight; a banking product wants an `httpOnly`, `SameSite=Strict` cookie with
  CSRF re-enabled server-side.
- **The admin dashboard mints its own `role: "admin"` token client-side** (`adminApi.ts:17-21`).
  This works only because dev tokens exist; under `prod`, `AuthController` is `@Profile("!prod")`
  and the dashboard cannot authenticate at all.

---

## 5. Test coverage

Strong and still growing. New this pass: `AssistantControllerStreamingTest`,
`WidgetHistoryStoreTurnAlignmentTest` (now Flyway-driven, which is the right way to keep the test
schema honest), `PolicySearchToolTenantIsolationTest`, `ApiExceptionHandlerTest`,
`PiiRedactorTest`.

**Gaps, in priority order:**

1. **§2.2 is invisible to the current suite.** `ApiExceptionHandlerTest` calls handler methods
   directly, so it asserts the handlers are correct while the *routing to* them is broken. A
   `MockMvc` slice test that POSTs an over-length message and asserts 400 is the fix.
2. **§2.1 has no coverage.** `WidgetHistoryStoreTurnAlignmentTest` tests the store in isolation;
   nothing tests `history()`'s alignment arithmetic against a sequence that includes a cancelled
   turn. That arithmetic is the fragile part.
3. **Still no test asserts tokens arrive incrementally.** `tokensStreamIncrementally` uses
   `Flux.just(...)`, which is fully materialized before subscription — it verifies boundary
   splitting, not incremental delivery. Drive it from a `Sinks.Many` you control and assert the
   first token event *before* emitting the last chunk. This is the exact regression class the
   test is named for.
4. **RAG endpoint tenant isolation** — the tool is covered, `/api/rag/ask` and `/ask-advanced`
   are not.
5. **`BudgetGuard` concurrency** — that N concurrent requests overshoot by at most one call's worth.
6. **Frontend still has no test runner.** `sse.ts`'s frame parser and `mergeById` are pure
   functions and the obvious first targets; a `route.ts` test would have caught §2.4 immediately.

`RUN_CONTAINER_TESTS` and `RUN_EVAL_TESTS` suites still don't run in a default `mvn test` —
confirm CI sets them, or they are decoration.

---

## Recommended order

1. **§2.5** — Redis password. Smallest fix here, and it's the difference between a documented
   scaling feature working and hard-failing every request.
2. **§2.2, §3.1** — the two wrong-status-code / fail-closed paths. Both are a handful of lines and
   both currently turn ordinary conditions into 500s.
3. **§2.1** — widget/turn alignment. It's a data-correctness bug that compounds silently and is
   already live in anything that has been cancelled once.
4. **§2.3, §2.4** — the proxy. The 30s cap undoes part of the benefit of incremental streaming;
   the retry path is dead code that throws when it runs.
5. **§3.2, §3.3, §3.6** — audit-chain anchoring, the two unboxing NPEs, the spending-summary sign
   bug. All small and independent.
6. **§3.4, §3.5, §3.7** — the unguarded embedding hop, ingest authorization, RAG input validation.
7. **§5** items 1–3, then §3.8.
