# AegisAI — Code Review

**Scope:** `aegis-merged` (production backend) + `aegis-lc4j/frontend` (shared Next.js UI).
`aegis-ai` and `aegis-lc4j/backend` are study/reference builds and are excluded — findings there
do not apply to production. Reviewed against the current working tree.

---

## Part 0 — Verified fixed since the last pass

Re-checked, confirmed closed. Listed so nothing gets re-reported.

| Was | Now |
|---|---|
| `/api/auth/token` mintable by anyone | `@Profile("!prod")` + `aegis.auth.dev-tokens` — `AuthController.java:14-15` |
| Default JWT secret only warned about | Constructor throws under `prod`, also rejects <32-byte secrets, before the port binds — `JwtService.java:46-53` |
| Arbitrary `tenantId` claim accepted | `aegis.known-tenants` allowlist enforced in `parse()` — `JwtService.java:80-82` |
| `/sse`, `/mcp/**` anonymous | `.authenticated()` — `SecurityConfig.java:60` |
| `/actuator/prometheus` public | `hasAuthority("PERM_admin:all")` — `SecurityConfig.java:62` |
| No CORS config | `corsConfigurationSource` bean, empty-by-default origins — `SecurityConfig.java:29-43` |
| `read-only` could move money | `money:transfer`, `account:write`, `profile:write`, `platform:admin` split — `JwtService.java:89-95`, applied across `BankingTools` |
| Negative transfer reversed direction | `amount.signum() <= 0` rejected in the domain — `BankingService.java:200-202`; sign/scale/cap checks in the tool |
| Model-controlled `confirmed` boolean | `ConfirmationGuard` — server-side, single-use, TTL'd token bound to `(userId, tool, args)` |
| RAG filter built by string concat | `FilterExpressionBuilder` — `RagController.java:39-40` |
| `/api/rag/**` bypassed guardrails | `GuardrailAdvisor` on `ragClient` — `RagConfig.java:31` |
| `ask-advanced` memory shared across users | `tenantId:userId:conversationId` — `AdvancedRagController.java:77` |
| Admin API unscoped | `resolveTenantFilter` / `requireOwnConversation` / `requireTenantAccess` on conversations, transcript, rag, audit/query, aml, cache/clear, budget |
| PII redaction destroyed the prompt | Replaces only the last `UserMessage` — `GuardrailAdvisor.java:65-74` |
| PAN regex redacted any digit run | Luhn-gated — `PiiRedactor.java:28-53` |
| Rate limit per-tenant only | Per-user + per-tenant buckets, configurable — `RateLimiter.java:77-86` |
| Redis rate limiter used client clock | `redis.call('TIME')` — `RateLimiter.java:26-27` |
| `turnSeq` from a sliding window | `assistant_turn_counter` table, atomic upsert — `WidgetHistoryStore.java:78-86` |
| Widget sinks dropped early events | `unicast().onBackpressureBuffer()` — `AssistantController.java:240-261` |
| Unbounded persist queues | Bounded 10k + drop policy + `awaitTermination` — `AuditTrail`, `WidgetHistoryStore` |
| Prompts logged at DEBUG by default | `INFO` default, `DEBUG` only under `dev` — `application.yml:127`, `:134-136` |
| Raw user message in ScopeGate logs | Logs `msgLength` — `ScopeGate.java:126` |
| No request validation | `ChatRequest` compact constructor: 4000-char cap, conversationId pattern — `AssistantController.java:152-177` |
| No error mapping | `ApiExceptionHandler` — 403 / 400 |
| "Delete chat" was cosmetic | `DELETE /api/assistant/history` + `deleteConversation()` wired |
| Conversation list leaked across identities | `useConversations(identityKey)`, per-identity storage key |
| No token refresh | `authFetch` 401 → clear → re-mint → retry; `exp` now read from the JWT |
| Almost no security tests | `SecurityWiringTest` (401/403/forged/cross-tenant), `BankingToolsTest` (caps, confirmation tokens, insufficient funds), `ConfirmationGuardTest`, `AuditTrailTamperTest`, `JwtServiceTest`, `CurrentUserTest`, `GuardrailAdvisorTest` |

---

## Part 1 — Regression introduced by the truncation-retry rework

### R1. The SSE endpoint no longer streams — CRITICAL

`AssistantController.java:442-459` (`collectAnswer`), consumed at `:299-315`.

```java
return responses
        .doOnNext(...)
        .map(cr -> ...getText())
        .collectList()                       // <- buffers the ENTIRE response
        .map(chunks -> { ... });
```

`collectList()` turns the token `Flux` into a `Mono` that completes only when the model is done.
`finalAnswer.flatMapMany` then emits every `token` event plus `meta` in one burst at `:315-351`.

The endpoint still *declares* `TEXT_EVENT_STREAM_VALUE` and the client still parses SSE frames, so
nothing looks broken — but time-to-first-token is now equal to full generation time. On the
qwen3.5:4b profile that is ~3s of blank screen per turn; on `:9b` it is 7–12s. The entire reason
this endpoint is SSE has been removed.

The retry needs the full text to judge truncation, so the two goals genuinely conflict. Options,
in order of preference:

1. **Drop the retry.** Fix truncation at the source (`num-predict: 512` in `application.yml:62`
   is the actual cause) and restore incremental emission at safe boundaries.
2. **Stream first, append on retry** — emit tokens live; if the completed text looks truncated,
   run the second call and emit only the *continuation* as additional token events.
3. **Retry only when it's cheap to detect early** — e.g. finish-reason `length` from the model
   metadata rather than a punctuation heuristic on the full text.

### R2. `looksTruncated` fires on almost every real answer

`AssistantController.java:462-468`

```java
char last = t.charAt(t.length() - 1);
return "!?.\"')]}”’".indexOf(last) < 0;
```

Any answer not ending in that punctuation set is "truncated". That includes:

- `"...Your balance is $2,500.00"` → ends in `0` → **retry**
- `"...card ending in 4412"` → ends in `2` → **retry**
- `"...status OPEN"` → ends in `N` → **retry**
- any bulleted list, any answer ending in an emoji, any answer ending in a code span

Balance and card answers are the single most common turn in this app, and every one of them
triggers a second full LLM call. Combined with R1 the worst-case turn is now two complete
generations before the user sees a character. Use the model's finish reason, or at minimum
exempt answers ending in a digit or letter that are already over some plausible-length floor.

### R3. Retry re-executes read-only tools, duplicating side effects

`AssistantController.java:309` calls `callModel.get()` again with the **same** `toolContext` and
the **same** `turnSeq`. `mutated` correctly blocks retry after a mutating tool (`:302-307`), but
read-only tools re-run:

- `widgetHistoryStore.save(memoryKey, turnSeq, ...)` fires again → **duplicate widget rows for
  the same turn**, which history replay merges back in (`hydrateAssistantMessage`), so a
  reloaded page can show a widget twice.
- `audit.toolCalled(...)` double-counts, so `/api/admin/overview` tool usage is inflated.
- Status events are emitted twice ("Fetching your accounts…" appears twice).

### R4. The first attempt's tokens are never charged

`AssistantController.java:341-346` records `collected.usage()` — the usage of the *surviving*
attempt only. A retried turn costs two generations and bills for one. Accumulate usage across
both attempts.

---

## Part 2 — Remaining security findings

### S1. `/api/admin/overview` and `/api/admin/events` are still cross-tenant

`AdminController.java:76-110` and `:113-115`. Every other admin endpoint was scoped;
these two were missed.

- `overview()` returns `audit.countsBySource()`, `audit.latency()`, `audit.toolUsage()`, the
  Micrometer `tokenSpend` list (tagged by tenant), and `tenantBudgets` — **all tenants**, for any
  `admin` token. An `achu-bank` admin reads `globex-bank`'s request volume, token spend, budget
  consumption and which tools they use.
- `events(limit)` returns the in-memory ring buffer verbatim: `tenant`, `user`, `conversationId`
  and the `question` preview for **every tenant**.

Both need the same `resolveTenantFilter` treatment. That means `AuditTrail` needs tenant-aware
accessors (`recent(tenant, limit)`, `countsBySource(tenant)`, …) rather than filtering in the
controller, since the ring buffer is a shared structure.

`timeseries()` (`:118-120`) has the same gap at lower sensitivity (aggregate counts only).
`verifyAuditChain()` (`:215`) is global but returns only a boolean and row counts.

### S2. MCP `PolicyTools` still takes the tenant from the caller

`mcp/PolicyTools.java:22-24`

```java
public String searchPolicies(
        @ToolParam(description = "tenant id, e.g. achu-bank or globex-bank") String tenantId,
        @ToolParam(...) String query)
```

`/mcp/**` is authenticated now, so this is no longer anonymous — but the tenant boundary is still
a parameter the *caller* chooses. Any authenticated user of any tenant enumerates every other
tenant's policy corpus over MCP. `PolicySearchTool` (the in-app twin) gets this right by reading
the tenant from `ToolContext`; this one should read it from the MCP session's principal, or the
tool should be removed from the MCP surface until per-session identity is wired.

### S3. `/api/rag/ask` has no conversation id — all callers share one memory bucket

`RagController.java:51-57` sets `TENANT_PARAM` and `USER_PARAM` but never
`ChatMemory.CONVERSATION_ID`. `ragClient` has `MessageChatMemoryAdvisor` attached
(`RagConfig.java:33`), which falls back to its default conversation id. Every `/api/rag/ask` call
from every user of every tenant therefore reads and writes the *same* conversation history.

`AdvancedRagController.java:77` builds the key correctly; `RagController` needs the same, or the
memory advisor should be dropped from this path.

### S4. `known-tenants` blank silently disables the allowlist

`JwtService.java:80` — `if (!knownTenants.isEmpty() && !knownTenants.contains(tenantId))`.
Setting `aegis.known-tenants=` (empty) turns the check off entirely and restores unbounded
tenant cardinality. Fail closed under `prod`: require a non-empty list the same way the secret
check does.

### S5. `AuthController` dev tokens default to on

`AuthController.java:15` — `matchIfMissing = true`. Under any non-prod profile (including a
staging deploy that forgot `-Dspring.profiles.active=prod`), anonymous callers still mint tokens
with `role: "admin"`. The `@Profile("!prod")` guard is the real protection; consider also
refusing `role=admin`/`platform-admin` from this endpoint so a forgotten profile is degraded
rather than total.

### S6. `ConfirmationGuard` state is per-instance

`ConfirmationGuard.java:30` — a plain `ConcurrentHashMap`. With more than one replica behind a
load balancer, the confirm call routinely lands on the instance that did not issue the token,
`verify` returns false, the model re-issues, and the user is asked to confirm forever. Move to
Redis (the infrastructure is already conditionally wired in `RedisConfig`) or make the token a
signed, self-contained value (HMAC over `userId|tool|argsHash|exp`) plus a small used-token set.

### S7. The confirmation token proves argument stability, not user consent

`BankingTools` returns `"CONFIRMATION_REQUIRED token=" + token` inside the **tool result string**,
which goes to the model. The model can call the tool twice in a row within one turn, echoing the
token it just received, with no user utterance in between.

This is a real improvement — the model can no longer change the amount between "confirm" and
"execute", which was the dangerous hole. But the guarantee should be stated precisely: it is
argument binding, not human-in-the-loop. For genuine consent the token must round-trip through
the client (emit it as a dedicated SSE `confirm` event, have the UI render an explicit
Approve/Cancel control, and require the client to POST it back).

### S8. Retrieved documents are never screened for injection

`InjectionScreen` is applied to the user message only — `GuardrailAdvisor.java:54` and
`AssistantController.java:211`. `PolicySearchTool.java:78-80` returns raw chunk text straight into
the model context. An instruction embedded in an ingested policy document, or in a transaction
merchant name, is unfiltered. In a RAG system this is the more realistic LLM01 vector than
anything a user types.

Screen retrieved text as well, and delimit it explicitly (`<document>…</document>` with a system
instruction that document content is data, never instructions).

### S9. GDPR erasure still misses the semantic cache and the vector store

`PrivacyController.java:34-41` clears `assistant_audit_event`, `assistant_widget_event`,
`spring_ai_chat_memory` and contact info. Not cleared:

- `SemanticCache` — an in-memory, tenant-scoped answer derived from the erased user's question
  survives for up to its TTL and is served to other users of that tenant. One line:
  `semanticCache.clear(tenantId)`.
- `vector_store` — anything added via `IngestionService.add`.

Also, `banking.updateContactInfo` uses `profiles.compute`, so erasing a user who has no profile
**creates** one containing `[erased]` — erasure that adds a record.

### S10. Audit hash chain: unkeyed, unbounded, single-instance

`AuditTrail`:
- `sha256Hex(prev + "|" + fields)` is unkeyed, so anyone with DB write access edits a row and
  recomputes the whole chain forward. Use an HMAC with a key the database does not hold.
- `verifyChain()` (`:~170`) loads **every** audit row into a `List<Object[]>` with no `LIMIT`.
  This is an OOM with a fuse on it. Stream with a `RowCallbackHandler`, or verify a bounded window.
- `lastHash` is per-instance. Two replicas interleave two chains and `verifyChain` reports
  tampering on a perfectly healthy system.

### S11. `IngestionService.ingestAll()` destroys the corpus non-transactionally

`IngestionService.java:36-45` — unchanged.

```java
var all = vectorStore.similaritySearch(...query("*").topK(10_000)...);
vectorStore.delete(all.stream().map(Document::getId).toList());
```

- Deletes **all tenants'** chunks, including anything added via `add()`.
- `topK(10_000)` silently leaves residue once the corpus exceeds that, so "idempotent" quietly
  stops being true.
- No transaction: if `vectorStore.add` at `:72` throws, the corpus is gone and every policy
  question answers "No matching policy passages found" until someone notices.
- `similaritySearch("*")` burns an embedding call to do what is really a table scan.

Delete by metadata filter per tenant inside a transaction, or write to a new namespace and swap.

### S12. `updateContactInfo` has no validation and no step-up auth

`BankingTools.java:~675`. Email and phone are the fraud-alert channel; changing them is the
classic account-takeover step. Currently: no format validation, no length limit, and the only
gate is a confirmation token the model holds (see S7). This specific tool warrants an
out-of-band verification (OTP to the *existing* contact) rather than an in-chat confirm.

### S13. `PiiRedactor` — Luhn gate trades false positives for false negatives

`PiiRedactor.java:28-38`. Luhn validation removed the false-positive problem, which was right.
The consequence is that a mistyped, partial, or deliberately-obfuscated PAN now passes through
**unredacted** to the model provider. For a control whose job is preventing egress, failing open
on malformed input is the wrong direction. Consider redacting any 13–19 digit run when it is
adjacent to card-ish context words, even if Luhn fails.

Still not redacted at all: **phone numbers** (the app stores and echoes `+1-555-0100`), names,
addresses. `IBAN` is uppercase-only. `SSN` matches only the dashed form.

### S14. `/actuator/metrics` and `/actuator/info` require only authentication

`application.yml:109` exposes `health,info,metrics,prometheus`. `SecurityConfig` explicitly
protects `/actuator/prometheus` (`:62`) and permits `/actuator/health/**` (`:57`), but `metrics`
and `info` fall through to `anyRequest().authenticated()` — any customer token reads them. Move
them behind `PERM_admin:all` or drop them from the exposure list.

### S15. CORS `allowCredentials(true)` with a wildcard origin fails at runtime

`SecurityConfig.java:36-39`. If anyone configures `aegis.cors.allowed-origins=*`, Spring throws
`IllegalArgumentException` on the first preflight rather than at startup. Validate the property
at bean-construction time, or use `setAllowedOriginPatterns`.

Since auth is a Bearer header and not a cookie, `allowCredentials(true)` isn't needed at all —
dropping it removes the footgun.

---

## Part 3 — Correctness and concurrency

### C1. `RateLimiter.allow(tenant, user)` consumes the tenant token even when the user is denied

`RateLimiter.java:81-85`

```java
boolean userOk   = allowBucket("aegis:ratelimit:user:" + tenantId + ":" + userId, ...);
boolean tenantOk = allowBucket("aegis:ratelimit:tenant:" + tenantId, ...);
return userOk && tenantOk;
```

Both are evaluated unconditionally, so a user who is already over their personal limit keeps
draining the shared tenant bucket on every rejected request — which is exactly the
noisy-neighbour problem the per-user bucket was introduced to solve. A user spamming at 50 rps
will starve every other user of that tenant. Short-circuit:

```java
if (!allowBucket(userKey, userCapacity, userRefillPerSec)) return false;
return allowBucket(tenantKey, tenantCapacity, tenantRefillPerSec);
```

### C2. `turnSeq` drifts permanently after any failed turn

`AssistantController.java:243` increments the persistent counter, then the model call runs. If it
errors (circuit open, timeout, tool exception), no assistant message is written to chat memory —
but the counter has already advanced.

`history()` reconstructs alignment as
`currentTurnSeq(memoryKey) - assistantCountInWindow` (`:120`), which assumes counter increments
and stored assistant messages are 1:1. After one failed turn every widget in that conversation is
attached to the wrong message, permanently.

Increment the counter only once the turn has actually produced an assistant message, or store the
turn ordinal alongside the message rather than deriving it.

### C3. `BudgetGuard` check-then-record is not atomic and fails closed on Redis

`BudgetGuard.java:74-87`. Concurrent requests all pass `checkOrThrow` before any `record` lands,
so a tenant overshoots by roughly `concurrency × tokens_per_call`. Acceptable for a soft quota,
but it should be a documented property rather than an accident — or folded into a single Lua
check-and-increment.

Separately, `Long.parseLong` at `:69` and `:92` is unguarded (a corrupted key throws), and every
Redis call propagates — so a Redis blip fails **closed** on the budget path and takes down every
chat turn. Decide the policy explicitly and catch.

### C4. `LlmGuard` — inconsistent operator ordering, thread pool leaks

`LlmGuard.java`:

- `call()` (`:88-92`) nests bulkhead → circuit breaker → time limiter (bulkhead outermost).
  `guard()` (`:110-115`) applies `transformDeferred(Bulkhead)` then `.timeout()` then
  `transformDeferred(CircuitBreaker)`, which makes the **circuit breaker outermost**. Bulkhead
  rejections are therefore recorded as breaker failures on the streaming path and can trip the
  breaker under pure concurrency pressure with no actual model failure.
- `cancelRunningFuture(true)` with `CompletableFuture.supplyAsync` cancels the future but cannot
  interrupt the blocking HTTP call underneath. The fixed 16-thread `pool` (`:57`) can fill with
  calls that already "timed out".
- `pool` has no `@PreDestroy` shutdown.
- `TIMEOUT = 30s` (`:50`) is hardcoded; every other tunable in this codebase is a property now.

### C5. `ScopeGate` blocks the request thread before the Flux is returned

`AssistantController.java:229` → `ScopeGate.inScope` → `classifier.prompt()...call()` — a
synchronous LLM round-trip (~250–800ms) on the Tomcat request thread, outside `LlmGuard`, before
the reactive pipeline is even constructed. It fails open internally so it can't error the request,
but it holds a thread and adds latency to every non-fastpath turn.

`widgetHistoryStore.nextTurnSeq` (`:243`) adds a synchronous DB round-trip on the same thread.

### C6. `ScopeGate.verdicts` can exceed its bound

`ScopeGate.java:~155`

```java
if (verdicts.size() >= MAX_VERDICTS) {
    verdicts.values().removeIf(CachedVerdict::expired);
}
verdicts.put(key, ...);
```

If nothing has expired, `removeIf` frees nothing and the `put` proceeds anyway. Under sustained
distinct-message load the map grows without limit. Use a size-bounded cache (Caffeine
`maximumSize` + `expireAfterWrite`).

### C7. In-memory rate-limit buckets never evict

`RateLimiter.java:53` — `buckets` is now keyed per *user*, so in the default non-Redis
configuration it grows with the total user count and is never pruned. Same class of leak as C6.

`ModelRouter.escalated` has the same shape: entries expire logically but are only removed when
that exact key is looked up again.

### C8. `allowLocal` holds a global lock

`RateLimiter.java:101` — `private synchronized boolean allowLocal(...)`. One monitor for every
tenant and user in the process, and it is now acquired **twice** per request (user bucket, then
tenant bucket). Under load this serializes the guardrail path. Per-key locking or an atomic
`compute` on the `ConcurrentHashMap` would remove it.

### C9. `ConfirmationGuard.canon` is ambiguous under separator collision

`ConfirmationGuard.java:70-76` joins arguments with `"###"`. An argument that itself contains
`###` (a dispute reason, an account nickname) can produce the same canonical string as a
different argument list. Length-prefix each field, or hash them individually.

Related: `verify` returns false on mismatch without consuming the token, so a caller can probe
argument variations against a live token until TTL. Low severity given the 5-minute window and
`userId` binding, but an attempt counter would close it.

### C10. Client disconnect is never audited

`AssistantController` — `audit.record(..., "llm", ...)` lives inside `flatMapMany` (`:348`), which
never runs when the subscription is cancelled. An abandoned turn consumes model capacity and
leaves no audit row. `doFinally` (`:383`) fires on cancel and could record a `"cancelled"` source.

### C11. `AuditTrail.record` size accounting can drift

`events.addFirst(...)`, `size.incrementAndGet()`, and `events.pollLast()` are three independent
operations. Under concurrency the deque can transiently exceed `MAX_EVENTS` or `size` can
disagree with the actual count. Bounded and harmless in practice; noting for completeness.

### C12. `AdvancedRagController` uses an ad-hoc confidence check

`AdvancedRagController.java:88` — `answer.toLowerCase().contains("i don't have that")` while the
assistant path uses `AnswerConfidence.looksLowConfidence`. Two different definitions of "don't
cache this". Use the shared one.

### C13. `AssistantController` doesn't pass `USER_PARAM`

`:293-295` sets `CONVERSATION_ID` and `TENANT_PARAM` only, while both RAG controllers pass
`USER_PARAM` too. Currently harmless — `GuardrailAdvisor` is a `CallAdvisor` and doesn't run on
`.stream()`, and the streaming path rate-limits per-user itself at `:200`. But if the advisor ever
gains a `StreamAdvisor` implementation, the assistant silently degrades to tenant-only rate
limiting. Pass it for consistency.

### C14. `looksLikeLeakedToolCall` false positives

`GuardrailAdvisor.java:103-112` blocks any output containing `{`, `}`, `"name"` and
(`"parameters"` or `"arguments"`). A legitimate answer that quotes a JSON payload — or a user
asking what a tool call looks like — is silently replaced with a canned failure message.

### C15. `ApiExceptionHandler` gaps

`config/ApiExceptionHandler.java`:

- `AccessDeniedException` → **403** unconditionally (`:17`). But `CurrentUser.get()` throws the
  same exception when there is *no* authenticated user, which should be 401.
- `e.getMessage()` is returned verbatim for any `IllegalArgumentException` (`:23`) — internal
  detail leakage into API responses.
- Catching `IllegalArgumentException` broadly turns genuine internal bugs into 400s, masking them
  in monitoring.
- No handler for `LlmGuard.LlmUnavailableException` → returns 500 where 503 is correct.
- No generic `Exception` handler, so unexpected errors fall through to Spring's default body.

---

## Part 4 — Domain model and data

### D1. `BankingService` is entirely in-memory

`ConcurrentHashMap` fields, seeded in the constructor. Every account, balance, card, dispute,
approval and ledger entry is lost on restart, and nothing is shared between replicas. This is
correct for a demo and the class documents it — but it is the single largest gap between this
codebase and "production banking backend", and it invalidates the ledger's durability guarantee.
The double-entry design is right; it needs a real datastore under it.

### D2. Encapsulation leaks in `BankingService`

- `pendingApprovals()` (`:192`) returns the **live mutable map**. Any caller can mutate approval
  state directly, bypassing every check.
- `getTransactions(accountId)` (`:107`) returns the live `CopyOnWriteArrayList`, not a copy.
- `ledgerFor(accountId)` (`:~195`) likewise.

Return `List.copyOf(...)` / `Map.copyOf(...)`.

### D3. `findTransaction` scans every tenant

`BankingService.java:111-117` flat-maps every transaction list in the process to find one id.
O(total transactions) per dispute/fraud call. Ownership is checked afterwards so it is not a
security hole, but it is an index waiting to be added.

### D4. No pagination anywhere

`searchTransactions`, `getSpendingSummary`, `listCards`, `AmlMonitor.scanTenant`,
`WidgetHistoryStore.loadForConversation`, `AdminController.transcript` all return complete result
sets. For `searchTransactions` and `getSpendingSummary` the whole list is also concatenated into a
string that goes into the model prompt — an account with a year of history would blow the context
window and the token budget in one call. Cap at N most recent, and say so in the tool result.

### D5. `requestAccountClosure` overloads the approval `amount` field

`BankingTools.java:746` passes `acct.balance()` as the approval amount. `Approval.amount` means
"money to be moved" everywhere else (card fee, provisional credit). A closure request is not a
$15,750.25 movement, and any downstream aggregation over `Approval.amount` will be wrong.

### D6. `computeIfPresent` can return null into `List.of(...)`

`freezeCard`, `unfreezeCard`, `setSpendingLimit`, `toggleMerchantCategory`, `renameAccount`,
`addEvidence`, `escalateCase` all use `computeIfPresent` and return `null` when the key vanished.
Callers do `emitCards(ctx, List.of(updated))`, which throws NPE on null. The prior `findCard`
check makes this a narrow race, but it is reachable.

### D7. `AmlMonitor` thresholds are hardcoded

`AmlMonitor.java:23-24` — `LARGE_TRANSACTION_THRESHOLD = 5000`, `VELOCITY_THRESHOLD = 3`. In a
real program these are per-tenant, per-jurisdiction, and tuned. Externalize as properties.

Also, its `LARGE_TRANSACTION` rule flags on `>= 5000` regardless of direction or currency, and
`VELOCITY` counts transfers the customer made between their own accounts — which the transfer
tool writes into `txns` (`BankingService.java:~247`), so self-transfers generate AML flags.

---

## Part 5 — Frontend (`aegis-lc4j/frontend`)

### F1. `stop` is dead code — there is no way to cancel a turn

`useChatStream.ts:195` exports `stop`, and `abortRef` is maintained at `:103`/`:133`. But
`ChatShell.tsx:41` destructures `{ messages, statuses, busy, historyError, send, loadHistory }` —
no `stop`. Nothing in the UI can abort an in-flight request. Given R1 (no incremental streaming),
a user now stares at a disabled composer for the full generation with no escape.

If it is wired back up, `sse.ts:189` swallows `AbortError` without invoking `onError`, so `busy`
would never reset — the composer would stay disabled forever. Both need fixing together.

### F2. `Composer` has no length limit

`Composer.tsx:42-61` — no `maxLength`. The backend rejects >4000 chars with a 400
(`ChatRequest.java:172`), which surfaces through `onError` as the generic "Something went wrong
reaching the assistant." Add `maxLength={4000}` and a counter past ~3500.

### F3. Proxy route: path traversal, no timeout, header stripping

`app/api/[...path]/route.ts`:

- `path.join("/")` is interpolated into `${base}/api/${path}` with no validation. A `..` segment
  reaches backend paths outside `/api` (e.g. `/actuator/*`). Validate each segment against
  `^[A-Za-z0-9._-]+$`.
- `forward()` has no timeout — a hung backend pins a Next.js worker indefinitely.
- Only `content-type` is copied from the backend response. `WWW-Authenticate`, `Retry-After`, and
  any future rate-limit headers are silently dropped.
- The localhost port-probe fallback (8082 → 8081 → 8080) is a dev convenience that will silently
  activate in production if `BACKEND_URL` is unset. Fail loudly instead.
- `X-Forwarded-For` is not propagated, so the backend can never see a real client IP — relevant
  if rate limiting ever moves to per-IP.

### F4. Tokens live in `sessionStorage`

`api.ts:25`, `:67`. Readable by any XSS. Acceptable for a demo; for a banking product this should
be an `httpOnly`, `SameSite=Strict` cookie with CSRF protection re-enabled on the backend.

Note the frontend still mints its own **admin** token client-side in `adminApi.ts` by POSTing
`role: "admin"` to `/api/auth/token`. That works only because dev tokens are enabled; under
`prod` the admin dashboard has no way to authenticate at all. There is currently no login UI.

### F5. `sse.ts` performs no runtime validation

`sse.ts:149-167` — `JSON.parse` then a straight `as CardData[]` cast. A malformed or hostile
payload propagates into component props unchecked. `Array.isArray` is checked for the list types
but nothing validates field shapes. Low risk (the backend is the only producer), but a
`zod`-style parse at the boundary would be cheap.

### F6. Markdown rendering is safe — verified

`MessageBubble.tsx:61` uses `ReactMarkdown` with `remarkGfm` and **no** `rehype-raw`.
react-markdown v10 escapes raw HTML and sanitizes `javascript:` URLs by default. No XSS. Noting
only because it was checked and is easy to break later by adding `rehype-raw`.

### F7. No lint config, no tests

`package.json` has no `lint` script, no ESLint config, and no test runner. The backend now has a
real test suite; the UI has none.

---

## Part 6 — Configuration and operations

### O1. `compose.yaml`

- **No volumes.** Postgres holds `assistant_audit_event`, `assistant_widget_event`,
  `spring_ai_chat_memory`, `assistant_turn_counter` and `vector_store`. Removing the container
  destroys the audit trail — the one thing that is supposed to be tamper-*evident*.
- Plaintext `POSTGRES_PASSWORD: aegis`, matching `application.yml:24`.
- Redis has no `requirepass`.
- No `restart:` policy, no memory/CPU limits, no pinned digest.

### O2. Datasource credentials are hardcoded with no prod override

`application.yml:21-24` sets url/username/password inline. The `prod` profile (`:190+`) overrides
only `logging.structured`. A prod deploy silently tries `localhost:5432` with `aegis/aegis`.
Use `${SPRING_DATASOURCE_URL}` etc. with no defaults so it fails fast.

### O3. Runtime DDL in production

`spring.ai.vectorstore.pgvector.initialize-schema: true` (`:69`),
`spring.ai.chat.memory.repository.jdbc.initialize-schema: always` (`:86`), plus `CREATE TABLE` /
`ALTER TABLE` executed at `ApplicationReadyEvent` in `AuditTrail.createSchema` and
`WidgetHistoryStore.createSchema`, and `ChatMemorySchemaFix`. Four separate places apply schema
at boot, all swallowing failures with a `log.warn`. A partially-applied schema starts the app in a
broken state that only shows up as runtime insert failures. Move to Flyway/Liquibase.

### O4. `num-predict: 512` is the real cause of truncation

`application.yml:62`. This is what R1/R2's retry machinery exists to paper over. A 512-token cap
on a model that must narrate tool results, cite policy, and ask a disambiguating question is
tight. Raising it (and setting a matching `maxTokens` on the Anthropic escalation client, which
is hardcoded to `1024` at `RouterConfig.java:75`) is a smaller change than the retry loop.

### O5. `spring.ai.retry.max-attempts: 3` sits inside the 30s time limiter

`application.yml:45-49`. Three attempts with 800ms → 1.6s → 3.2s backoff, all inside
`LlmGuard`'s single 30s budget, means a retrying call eats the whole budget and reports a timeout
rather than the underlying error.

### O6. No request-size or async-timeout limits

No `spring.servlet.multipart.*`, no `server.max-http-request-header-size`, no
`spring.mvc.async.request-timeout`. The 4000-char message cap is enforced after the body is fully
read.

### O7. `aegis.cache.similarity-threshold: 0.62` is configured but unused

`application.yml:104`. `SemanticCache.THRESHOLD` is a `private static final double = 0.62`
constant — the property does nothing. Either wire it or remove it; a config knob that silently
does nothing is worse than no knob.

### O8. Micrometer `tenant` tag cardinality is now bounded — but `model` is not

`TokenAuditAdvisor.java:48-51` tags counters with `tenant` (now allowlisted, good) and `model`.
`model` comes from response metadata; with the router escalating between clients that's a small
fixed set today, but it is unvalidated.

### O9. Repo hygiene

- `aegis-merged/target/**` (compiled `.class` files, a copy of `application.yml`) and
  `aegis-lc4j/frontend/.next/**` are tracked in git. Add `.gitignore` entries and
  `git rm -r --cached`.
- `.DS_Store` at the repo root is tracked.
- Now that `aegis-ai` and `aegis-lc4j/backend` are study-only, say so in the root `README.md` —
  it currently presents all three as parts of one system ("Core backend", "Merged services"),
  which is how a future reader ends up fixing a bug in the wrong tree. Consider moving them under
  `study/` or tagging and deleting them.

---

## Part 7 — Test coverage

Now genuinely good in the areas that matter most. `SecurityWiringTest` covers 401 without a token,
403 for a customer hitting admin, forged-token rejection, and cross-tenant admin denial plus
platform-admin allow. `BankingToolsTest` covers confirmation-token issuance, single-use, argument
binding, non-positive and above-cap transfers, insufficient funds, foreign destination, and
provisional credit above the disputed amount. `ConfirmationGuardTest`, `AuditTrailTamperTest`,
`JwtServiceTest`, `CurrentUserTest`, `GuardrailAdvisorTest` all exist.

Gaps that remain:

1. **`AssistantController.stream` has no test** — 300 lines, 10 sinks, manual guardrails, a retry
   loop, and the R1–R4 regressions above would all have been caught by one test asserting that
   token events arrive before the model completes.
2. **RAG tenant isolation is untested.** `SecurityWiringTest` covers admin conversations; nothing
   asserts that tenant A's token cannot retrieve tenant B's *documents* through
   `/api/rag/ask`, `/api/rag/ask-advanced`, or `searchPolicies`.
3. **`WidgetHistoryStore` turn alignment** — no test for the C2 drift, or for history replay after
   the memory window has pruned older messages.
4. **`PiiRedactor` edge cases** — no test that a Luhn-valid PAN is redacted and an invalid one is
   not, no email/IBAN/SSN cases.
5. **`RateLimiter` two-bucket interaction** — C1 would be caught by a test asserting that a
   user-limited request does not decrement the tenant bucket.
6. **`ApiExceptionHandler`** — no test for the 401-vs-403 distinction.
7. Several suites remain env-gated (`RUN_CONTAINER_TESTS`, `RUN_EVAL_TESTS`) and so do not run in
   a default `mvn test`. Make sure CI sets them.

---

## Suggested order

1. **R1–R4** — the streaming regression is user-visible on every single turn and is the most
   expensive thing in the codebase right now.
2. **S1, S3** — the two remaining cross-tenant leaks (admin overview/events, shared RAG memory).
3. **C1, C2** — rate-limit bucket consumption and widget-history drift.
4. **S2, S8, S9, S11** — MCP tenant parameter, document injection screening, erasure gaps,
   ingestion destructiveness.
5. **O1, O2, O3** — persistence, secrets, schema management. Nothing above matters if the audit
   trail lives in a container with no volume.
6. Test gaps 1–5, then the rest.
