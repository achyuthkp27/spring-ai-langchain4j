# AegisAI — Code Review

**Scope:** `aegis-merged` (production backend) + `aegis-lc4j/frontend`. `aegis-ai` and
`aegis-lc4j/backend` are study builds — excluded. Frontend visual/interaction findings and the
redesign that shipped now live in **[UI_REVIEW.md](./UI_REVIEW.md)**; this file is backend +
frontend data/logic.

**Build state:** `mvn -o test` green; frontend `vitest run` 9/9 green, `tsc --noEmit` and
`eslint` clean, `next build` succeeds.

**Headline:** the previous pass's open list is now **almost entirely closed**, verified against
current source. Four commits did it — `0687dee` (streaming/truncation), `b1c95fd` (Redis
failover + config defaults), `c5f49a8` (input validation, audit-chain anchoring, Vitest),
`2c26d8b` (UI). What was P1/P2 is gone; what remains is a short tail of by-design trade-offs and
one genuinely-unsynchronized-but-benign read.

---

## 1. Closed this pass — verified in current source

**Validation returns the right status (was P1 §2.2)**
`ApiExceptionHandler` now has an explicit `HttpMessageNotReadableException` handler that unwraps
`IllegalArgumentException` from the cause chain → **400 with the reason** (`:39-48`), plus a
`MethodArgumentTypeMismatchException` → 400 handler (`:50-54`). `AssistantControllerValidationTest`
is a `MockMvc` slice asserting an over-length message returns 400, not 500 — which is exactly the
routing the old unit test couldn't exercise. Chat and both RAG endpoints share the new
`ChatInputValidation` helper (`RagController:31-36`, `AdvancedRagController`), closing the RAG
input-validation gap (was §3.7) at the same time.

**Redis is usable and degrades correctly (was P1 §2.5, P2 §3.1)**
`RedisConfig` builds a `RedisStandaloneConfiguration` and reads `spring.data.redis.username`/
`password` (`:20-28`), so it authenticates against the `--requirepass` Redis in `compose.yaml`.
`RateLimiter.allowRedis` now catches and **falls through to `allowLocal`** (`:102-111`) instead of
propagating a 500 — a Redis blip degrades to per-instance limits rather than taking down every
chat turn. `RedisConfigTest` covers the wiring.

**Widget/turn alignment survives a cancel (was P1 §2.1)**
`persistCancelledTurnWidgets` saves a cancelled turn's widgets under `currentTurnSeq + 1`
**without advancing the counter** (`AssistantController:389-394`), so `history()`'s arithmetic
stays aligned and the cancelled widgets are reclaimed by the retry — the stopgap the last review
recommended. `AssistantControllerHistoryAlignmentTest` proves widgets stay attached to the right
message across a cancelled turn.

**Truncated answers aren't cached (was §2.2)**
`isSafeToCache(redactedAnswer, dynamic || mutated || truncated)` (`:292`) — a `finish_reason:
length` answer is neither cached nor allowed to look complete, and it escalates the next turn
(`:295`).

**Audit chain is anchored (was §3.2)**
`verifyChain` now asserts the first non-legacy row's `prev_hash == "GENESIS"` and reports a
distinct `chainHeadMissing` verdict (`AuditTrail:219-227`), so head-truncation of the audit table
no longer validates clean. `AuditTrailTamperTest` was expanded.

**The unguarded model/JDBC hops are gone (was §3.4)**
`SemanticCache` wraps both `embed` calls — lookup (`:79`) and put (`:108`) — in `llmGuard.call(...)`,
and `CacheConfig` injects the shared `LlmGuard` bean rather than a throwaway. The reactive
terminal block runs on `Schedulers.boundedElastic()` (`AssistantController:312`), moving the
`nextTurnSeq` JDBC round-trip off the Reactor thread. `pendingWidgets.clear()` after the terminal
save (`:301`) closes the double-persist race.

**Token-usage unboxing NPEs (was §3.3)** — both sites now null-check `getTotalTokens()`
(`GuardrailAdvisor:86`, `TokenAuditAdvisor:36`).

**Spending summary no longer double-counts (was §3.6)** — credits go to `credits`, only debits
enter `byCategory` (`BankingTools:576-581`). A $400 withdrawal + $400 refund no longer reads as
$800 of spending.

**Ingest is platform-admin only (was §3.5)** — `/api/admin/ingest/**` requires
`PERM_platform:admin` (`SecurityConfig:64`), not the plain `admin` role.

**pgvector schema has one owner (was §3.8)** — `V2__vector_store.sql` owns `vector_store`;
`spring.ai.vectorstore.pgvector.initialize-schema: false`.

**Smaller items** — `PrivacyController.erase` rejects blank tenant/user (`:34-39`);
`BankingService.renameAccount` is now `synchronized`, matching `transfer` (`:103`);
`setCardSpendingLimit` and `toggleMerchantCategoryBlock` now require a `confirmationToken`
(`:637`, `:686`); `ConfirmationGuard`'s in-memory path removes the token on arg-mismatch
(`:88`), matching the Redis burn-on-mismatch behaviour.

---

## 2. Still open

### 2.1 `BudgetGuard` check-and-record is two-phase by construction — bounded overshoot

`checkOrThrow` (CHECK script) runs at request start; `record` (INCR script) runs at response end,
because the token count isn't known until then. Concurrent requests all pass CHECK before any
INCR lands, so a tenant can exceed its daily budget by roughly `concurrency × tokens_per_call`.

This is now **acknowledged and tested** rather than silent — `BudgetGuardTest` has
`concurrentRecordingDoesNotLoseUpdates` and a race-past-budget case asserting the tenant is
"reliably blocked" once over. The parse-failure and fail-closed sub-points from the original
finding are already fixed (guarded parse, explicit fail-open). The remaining overshoot is inherent
to charging after generation; a true fix needs pre-reservation of an estimated cost, which is a
larger design change. **Fine to leave as a documented soft-quota property** — just don't describe
the budget as a hard cap.

### 2.2 `doFinally` reads `state.sanitized.length()` cross-thread (benign)

`AssistantController:346` — the cancel path reads the length of a `StringBuilder` written on the
`concatMap` thread. Unsynchronized, but the only consequence is a possibly-stale character count
in one audit row. Left as-is is defensible; a `volatile int` length mirror would remove it.

---

## 3. Deliberate trade-offs — document, don't fix

Unchanged and still correctly characterised:

- **Confirmation tokens bind arguments, not consent** — the token round-trips through the model,
  not the client, so the model can echo it within one turn. `BankingTools`' Javadoc should say
  *argument binding*. Real consent needs a dedicated SSE `confirm` event + explicit Approve/Cancel.
- **`PiiRedactor` Luhn gate fails open on malformed PANs**, mitigated by `CARD_CONTEXT`. Names and
  addresses uncovered.
- **`BankingService` is in-memory** — balances, cards, disputes, ledger lost on restart, unshared
  between replicas. The audit trail around it *is* durable and HMAC-chained.
- **`updateContactInfo` has no step-up auth** — the classic ATO step; only gate is a model-held token.
- **`AuditTrail` HMAC key is application-held** — an app-level compromise can forge the chain;
  genuine tamper-evidence needs WORM/external notary.
- **Dev tokens default on** (`AuthController`, `@Profile("!prod")`) and **the admin dashboard mints
  its own client-side token** — both work only because dev tokens exist; under `prod` the dashboard
  cannot authenticate at all. Fine for a demo; a real deployment needs a login flow.

---

## 4. Test coverage — now strong

New this pass: `AssistantControllerValidationTest` (MockMvc, over-length → 400),
`AssistantControllerHistoryAlignmentTest` (cancel-turn alignment),
`AssistantControllerStreamingTest` now drives a `Sinks.Many` source and asserts the first token
event arrives **before** the source completes — the exact incremental-delivery regression class,
finally covered. `RagRequestValidationTest`, `BudgetGuardTest` (concurrency), `RedisConfigTest`,
`PrivacyControllerTest`. **Frontend now has a runner**: Vitest with `sse.test.ts`,
`useChatStream.test.ts`, `route.test.ts` (9 tests) — the pure-function boundaries recommended last
pass.

**Remaining gaps, minor:**
1. No end-to-end test of `/api/rag/ask` / `ask-advanced` tenant isolation (the tool is covered).
2. Container/eval suites still env-gated (`RUN_CONTAINER_TESTS`, `RUN_EVAL_TESTS`) — confirm CI
   sets them or they're decoration.

---

## 5. Recommended next

The backend is in good shape. In priority order:

1. **Amend the two Javadocs** (§3.1 confirmation = argument binding; note the `updateContactInfo`
   step-up gap) — leaving the consent claim overstated is the one remaining correctness-of-*docs*
   risk, and it's five minutes.
2. **Decide the budget story** (§2.1) — either document it as a soft quota or add pre-reservation.
3. **RAG endpoint isolation test** (§4.1).
4. Optionally close §2.2 with a `volatile` length mirror.
