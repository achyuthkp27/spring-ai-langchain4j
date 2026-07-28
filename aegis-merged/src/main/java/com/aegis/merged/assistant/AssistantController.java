package com.aegis.merged.assistant;

import com.aegis.merged.admin.AuditTrail;
import com.aegis.merged.guardrails.BudgetGuard;
import com.aegis.merged.guardrails.GuardrailAdvisor;
import com.aegis.merged.guardrails.InjectionScreen;
import com.aegis.merged.guardrails.LlmGuard;
import com.aegis.merged.guardrails.PiiRedactor;
import com.aegis.merged.guardrails.RateLimiter;
import com.aegis.merged.rag.SemanticCache;
import com.aegis.merged.security.CurrentUser;
import com.aegis.merged.security.Principal;
import com.aegis.merged.tools.BankingTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The unified assistant: ONE endpoint for the user. The model orchestrates the
 * conversation and picks tools; the identity (from headers → a JWT in prod) is
 * passed via toolContext and drives all authorization and tenant scoping.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final ChatClient assistant;
    private final BankingTools bankingTools;
    private final PolicySearchTool policySearchTool;
    private final SemanticCache semanticCache;
    // Streaming bypasses the CallAdvisor guardrails, so the stream path enforces them
    // itself: input gates up front, output sanitization at line boundaries.
    private final RateLimiter rateLimiter;
    private final BudgetGuard budgetGuard;
    private final InjectionScreen injectionScreen;
    private final PiiRedactor piiRedactor;
    private final ScopeGate scopeGate;
    private final LlmGuard llmGuard;
    private final AuditTrail audit;
    private final ChatMemory chatMemory;
    private final ModelRouter router;
    private final ObjectProvider<ChatClient> escalationClient;
    private final WidgetHistoryStore widgetHistoryStore;
    // JavaTimeModule registered explicitly: Transaction carries a LocalDate, and this is a
    // plain ObjectMapper (not the Spring-managed one autoconfigured with JSR310) — without
    // it, serializing a transaction silently failed and emitted an empty "{}" cards/tx event.
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AssistantController(@Qualifier("assistantClient") ChatClient assistant,
                              BankingTools bankingTools,
                              PolicySearchTool policySearchTool,
                              SemanticCache semanticCache,
                              RateLimiter rateLimiter,
                              BudgetGuard budgetGuard,
                              InjectionScreen injectionScreen,
                              PiiRedactor piiRedactor,
                              ScopeGate scopeGate,
                              LlmGuard llmGuard,
                              AuditTrail audit,
                              ChatMemory chatMemory,
                              ModelRouter router,
                              @Qualifier("escalationClient") ObjectProvider<ChatClient> escalationClient,
                              WidgetHistoryStore widgetHistoryStore) {
        this.assistant = assistant;
        this.bankingTools = bankingTools;
        this.policySearchTool = policySearchTool;
        this.semanticCache = semanticCache;
        this.rateLimiter = rateLimiter;
        this.budgetGuard = budgetGuard;
        this.injectionScreen = injectionScreen;
        this.piiRedactor = piiRedactor;
        this.scopeGate = scopeGate;
        this.llmGuard = llmGuard;
        this.audit = audit;
        this.chatMemory = chatMemory;
        this.router = router;
        this.escalationClient = escalationClient;
        this.widgetHistoryStore = widgetHistoryStore;
    }

    /** One widget event attached to a historical assistant message — {@code payload} is
        embedded as real JSON (not a string), matching the shape the live SSE event carried. */
    public record WidgetEvent(String type, com.fasterxml.jackson.databind.JsonNode payload) {
    }

    public record HistoryMessage(String role, String text, List<WidgetEvent> widgets) {
    }

    /**
     * Restore a conversation after a page refresh. The memory key is built from the
     * VERIFIED principal, so a user can only ever read their own conversations.
     *
     * Widget events (cards/accounts/transactions/case/approval/citations) are re-attached
     * per assistant message by turnSeq — see WidgetHistoryStore for why a page reload would
     * otherwise lose every rich widget and fall back to the model's plain text.
     */
    @GetMapping("/history")
    public List<HistoryMessage> history(@RequestParam(defaultValue = "default") String conversationId) {
        Principal principal = CurrentUser.get();
        String memoryKey = principal.tenantId() + ":" + principal.userId() + ":" + conversationId;
        var messages = chatMemory.get(memoryKey);
        if (messages == null) return List.of();

        Map<Integer, List<WidgetHistoryStore.WidgetRow>> byTurn = new java.util.HashMap<>();
        for (var row : widgetHistoryStore.loadForConversation(memoryKey)) {
            byTurn.computeIfAbsent(row.turnSeq(), k -> new ArrayList<>()).add(row);
        }

        List<HistoryMessage> out = new ArrayList<>();
        int assistantSeq = 0;
        for (var m : messages) {
            var type = m.getMessageType();
            if (type == org.springframework.ai.chat.messages.MessageType.USER) {
                out.add(new HistoryMessage("user", m.getText() == null ? "" : m.getText(), List.of()));
            } else if (type == org.springframework.ai.chat.messages.MessageType.ASSISTANT) {
                assistantSeq++;
                List<WidgetEvent> widgets = new ArrayList<>();
                for (var row : byTurn.getOrDefault(assistantSeq, List.of())) {
                    try {
                        widgets.add(new WidgetEvent(row.widgetType(), json.readTree(row.payload())));
                    } catch (Exception e) {
                        // A malformed historical payload shouldn't break the whole reload.
                    }
                }
                out.add(new HistoryMessage("assistant", m.getText() == null ? "" : m.getText(), widgets));
            }
        }
        return out;
    }

    public record ChatRequest(String conversationId, String message) {
        public ChatRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
        }
    }

    // source: "cache" = answered from semantic cache (~ms, no LLM);
    //         "llm"   = full model call (~seconds).
    // similarity/matchedQuestion are populated only for cache hits, so a JSON export
    // shows *why* a cache answer was chosen (and reveals over-matches like
    // "what is dispute?" colliding with the deadline entry).
    public record ChatReply(String conversationId, String answer, String source,
                            long elapsedMs, Double similarity, String matchedQuestion) {
        static ChatReply fromCache(String cid, SemanticCache.Hit hit, long ms) {
            return new ChatReply(cid, hit.answer(), "cache", ms, hit.similarity(), hit.matchedQuestion());
        }
        static ChatReply fromLlm(String cid, String answer, long ms) {
            return new ChatReply(cid, answer, "llm", ms, null, null);
        }
    }

    /**
     * THE assistant endpoint — one API, always streaming. Server-Sent Events give the
     * first token in hundreds of milliseconds instead of a multi-second blank wait.
     *
     * Streaming does NOT run the CallAdvisor guardrails, so this method enforces them
     * itself: rate/budget/injection gate BEFORE the stream starts, and PII redaction /
     * tool-leak suppression run on each completed line BEFORE it is flushed to the client
     * (you can't un-send a token, so we sanitize at line boundaries, not per-token).
     *
     * Events: {"t": "..."} token chunks, then a terminal {"source","elapsedMs",...} meta.
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        long start = System.nanoTime();
        Principal principal = CurrentUser.get();
        String tenantId = principal.tenantId();
        String userId = principal.userId();
        String cid = request.conversationId();
        String memoryKey = tenantId + ":" + userId + ":" + cid;

        // Every outcome below is recorded to the AuditTrail with its SPECIFIC cause
        // (the client-facing ChatReply keeps the coarse source: blocked/cache/llm).
        String redactedQ = piiRedactor.redact(request.message());

        // --- INPUT GUARDRAILS (advisors are skipped on the streaming path) ---
        if (!rateLimiter.allow(tenantId)) {
            audit.record(tenantId, userId, cid, "blocked-rate", ms(start), 0, redactedQ);
            return oneShot(cid, "You're sending requests too quickly. Please slow down and retry.",
                    "blocked", start, null);
        }
        try {
            budgetGuard.checkOrThrow(tenantId);
        } catch (BudgetGuard.BudgetExceededException e) {
            audit.record(tenantId, userId, cid, "blocked-budget", ms(start), 0, redactedQ);
            return oneShot(cid, "Token budget exceeded for this tenant.", "blocked", start, null);
        }
        if (injectionScreen.screen(request.message()).flagged()) {
            audit.record(tenantId, userId, cid, "blocked-injection", ms(start), 0, redactedQ);
            return oneShot(cid, GuardrailAdvisor.REFUSAL + " (suspected prompt injection)",
                    "blocked", start, null);
        }

        // --- SEMANTIC CACHE: instant, emit the whole answer as one chunk ---
        var hit = semanticCache.lookup(tenantId, request.message());
        if (hit.isPresent()) {
            audit.record(tenantId, userId, cid, "cache", ms(start), hit.get().answer().length(), redactedQ);
            return oneShot(cid, hit.get().answer(), "cache", start, hit.get());
        }

        // --- SCOPE GATE (code-enforced): decline off-domain before streaming ---
        // Conversation key lets a follow-up ("the 3rd one") be judged in context.
        if (!scopeGate.inScope(request.message(), memoryKey)) {
            audit.record(tenantId, userId, cid, "blocked-scope", ms(start), 0, redactedQ);
            return oneShot(cid, ScopeGate.REDIRECT, "blocked", start, null);
        }

        // --- STREAM the model, sanitizing each completed line before it egresses ---
        String redactedInput = redactedQ;
        AtomicBoolean dynamic = new AtomicBoolean(false);
        AtomicBoolean toolFailed = new AtomicBoolean(false);
        // Shared across the first attempt AND a retry (see MUTATED_KEY) so a retry can check
        // whether the FIRST attempt already changed state before deciding to run again.
        AtomicBoolean mutated = new AtomicBoolean(false);

        // Tool-progress side channel: tools push human-readable status lines here and
        // they egress immediately as SSE "status" events — the user sees "Searching
        // policy documents…" instead of a silent multi-second gap.
        Sinks.Many<String> statusSink = Sinks.many().multicast().onBackpressureBuffer();
        java.util.function.Consumer<String> statusFn = statusSink::tryEmitNext;

        // 1-indexed ordinal of the assistant reply about to be produced, computed from the
        // PRIOR message count (this turn's messages aren't in memory yet) — how WidgetHistoryStore
        // re-attaches persisted widget events to the right historical message after a reload.
        var priorMessages = chatMemory.get(memoryKey);
        int turnSeq = 1 + (priorMessages == null ? 0 : (int) priorMessages.stream()
                .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.ASSISTANT)
                .count());

        // Structured data side channels: tools push REAL records here so the UI can render
        // actual widgets (card faces, balance tiles, a transaction list, a case status card)
        // from real data, not by parsing them back out of the model's prose — see the
        // BankingTools.*_KEY constants. Each also persists via WidgetHistoryStore so a page
        // reload doesn't lose the widget (see that class). widgetChannel() below is the one
        // place that composes "emit to the live SSE sink" + "persist for history replay" —
        // nine of these existed as hand-copied pairs before they started drifting.
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.Card>> cardsSink = Sinks.many().multicast().onBackpressureBuffer();
        var cardsFn = widgetChannel(cardsSink, memoryKey, turnSeq, "cards");
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.Account>> accountsSink = Sinks.many().multicast().onBackpressureBuffer();
        var accountsFn = widgetChannel(accountsSink, memoryKey, turnSeq, "accounts");
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.Transaction>> transactionsSink = Sinks.many().multicast().onBackpressureBuffer();
        var transactionsFn = widgetChannel(transactionsSink, memoryKey, turnSeq, "transactions");
        Sinks.Many<com.aegis.merged.domain.BankingService.DisputeCase> casesSink = Sinks.many().multicast().onBackpressureBuffer();
        var casesFn = widgetChannel(casesSink, memoryKey, turnSeq, "case");
        Sinks.Many<com.aegis.merged.domain.BankingService.Approval> approvalsSink = Sinks.many().multicast().onBackpressureBuffer();
        var approvalsFn = widgetChannel(approvalsSink, memoryKey, turnSeq, "approval");
        Sinks.Many<java.util.List<BankingTools.Citation>> citationsSink = Sinks.many().multicast().onBackpressureBuffer();
        var citationsFn = widgetChannel(citationsSink, memoryKey, turnSeq, "citations");
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.LedgerEntry>> ledgerSink = Sinks.many().multicast().onBackpressureBuffer();
        var ledgerFn = widgetChannel(ledgerSink, memoryKey, turnSeq, "ledger");
        Sinks.Many<com.aegis.merged.domain.BankingService.CustomerProfile> profileSink = Sinks.many().multicast().onBackpressureBuffer();
        var profileFn = widgetChannel(profileSink, memoryKey, turnSeq, "profile");
        Sinks.Many<BankingTools.SpendingSummary> statementSink = Sinks.many().multicast().onBackpressureBuffer();
        var statementFn = widgetChannel(statementSink, memoryKey, turnSeq, "statement");

        // --- MODEL CASCADE: cheap upfront classification, no extra LLM round-trip ---
        ModelRouter.Tier tier = router.decide(request.message(), memoryKey);
        ChatClient model = tier == ModelRouter.Tier.COMPLEX
                ? escalationClient.getIfAvailable(() -> assistant)
                : assistant;
        if (model != assistant) {
            statusFn.accept("Using extended reasoning…");
        }

        // Guarded with the same circuit breaker + timeout as the blocking path. Factored into
        // a supplier so a truncated first attempt (see looksTruncated below) can be retried.
        Map<String, Object> toolContext = Map.ofEntries(
                Map.entry(BankingTools.PRINCIPAL_KEY, principal),
                Map.entry(BankingTools.DYNAMIC_ACCESS_KEY, dynamic),
                Map.entry(BankingTools.STATUS_KEY, statusFn),
                Map.entry(BankingTools.TOOL_FAILED_KEY, toolFailed),
                Map.entry(BankingTools.MUTATED_KEY, mutated),
                Map.entry(BankingTools.CARDS_KEY, cardsFn),
                Map.entry(BankingTools.ACCOUNTS_KEY, accountsFn),
                Map.entry(BankingTools.TRANSACTIONS_KEY, transactionsFn),
                Map.entry(BankingTools.CASES_KEY, casesFn),
                Map.entry(BankingTools.APPROVALS_KEY, approvalsFn),
                Map.entry(BankingTools.CITATIONS_KEY, citationsFn),
                Map.entry(BankingTools.LEDGER_KEY, ledgerFn),
                Map.entry(BankingTools.PROFILE_KEY, profileFn),
                Map.entry(BankingTools.STATEMENT_KEY, statementFn));
        java.util.function.Supplier<Flux<String>> callModel = () -> llmGuard.guard(model.prompt()
                .system(sp -> sp.param("bankName", TenantNames.displayName(tenantId)))
                .user(redactedInput)
                .tools(bankingTools, policySearchTool)
                .toolContext(toolContext)
                .advisors(a -> a
                        .param(ChatMemory.CONVERSATION_ID, memoryKey)
                        .param(GuardrailAdvisor.TENANT_PARAM, tenantId))
                .stream()
                .content());

        // Buffer the FULL answer server-side before anything reaches the client, instead of
        // flushing each token as it arrives. A known Spring AI bug
        // (github.com/spring-projects/spring-ai/issues/5167) can silently truncate streamed
        // content mid-sentence across a tool-calling round with no exception thrown — verified
        // live: the identical prompt sent directly to Ollama (bypassing Spring AI) always
        // completes cleanly, so this is Spring's streaming+tool-call aggregation, not the model
        // or our prompt. Buffering trades the token-by-token typing feel (the status chips
        // already cover the wait) for correctness: a truncated-looking result gets one retry
        // before the customer ever sees it.
        Mono<String> firstAttempt = collectAnswer(callModel.get());
        Mono<String> finalAnswer = firstAttempt.flatMap(text -> {
            if (!looksTruncated(text)) return Mono.just(text);
            if (mutated.get()) {
                // A retry would replay the SAME tool call — freeze/transfer/file the same
                // thing twice. Accept the truncated text over risking a double mutation.
                log.warn("assistant.answer.looksTruncated NOT retrying (mutating tool already ran) "
                        + "key={} chars={}", memoryKey, text.length());
                return Mono.just(text);
            }
            log.warn("assistant.answer.looksTruncated retrying once key={} chars={}", memoryKey, text.length());
            return collectAnswer(callModel.get())
                    .map(retry -> looksTruncated(retry) && retry.length() <= text.length() ? text : retry)
                    .onErrorReturn(text);
        });

        Flux<ServerSentEvent<String>> answer = finalAnswer.flatMapMany(rawFull -> {
            List<ServerSentEvent<String>> out = new ArrayList<>();
            String remaining = rawFull;
            while (true) {
                // Flush everything up to the last SAFE boundary (newline or sentence end).
                // These boundaries never fall inside a PAN/SSN/IBAN/email, so redacting the
                // flushed segment can't miss a value that's split across the boundary.
                int b = lastSafeBoundary(remaining);
                if (b <= 0) break;
                out.add(tokenEvent(remaining.substring(0, b)));   // sanitized inside
                remaining = remaining.substring(b);
            }
            if (!remaining.isEmpty()) out.add(tokenEvent(remaining));

            // Redact BEFORE caching / meta: token events are redacted per segment above, but
            // rawFull is the raw model output — a cache hit or meta replay must never leak PII.
            String redactedAnswer = piiRedactor.redact(rawFull);
            if (isSafeToCache(redactedAnswer, dynamic.get())) {
                semanticCache.put(tenantId, request.message(), redactedAnswer);
            }
            router.markLowConfidence(memoryKey, redactedAnswer, toolFailed.get());
            // Rough token accounting so the budget guard still moves on the stream path
            // (streamed content() carries no usage metadata). ~4 chars/token heuristic.
            budgetGuard.record(tenantId, Math.max(1, redactedAnswer.length() / 4));
            long ms = ms(start);
            audit.record(tenantId, userId, cid, "llm", ms, redactedAnswer.length(), redactedQ);
            out.add(metaEvent(new ChatReply(cid, redactedAnswer, "llm", ms, null, null)));
            return Flux.fromIterable(out);
        });

        // Merge tool-progress events into the answer stream; close the side channels
        // when the main stream ends so the merged flux completes.
        Flux<ServerSentEvent<String>> statusEvents = statusSink.asFlux()
                .map(s -> ServerSentEvent.<String>builder().event("status")
                        .data(toJson(Map.of("s", s))).build());
        Flux<ServerSentEvent<String>> cardEvents = cardsSink.asFlux()
                .map(cards -> ServerSentEvent.<String>builder().event("cards")
                        .data(toJson(cards)).build());
        Flux<ServerSentEvent<String>> accountEvents = accountsSink.asFlux()
                .map(accounts -> ServerSentEvent.<String>builder().event("accounts")
                        .data(toJson(accounts)).build());
        Flux<ServerSentEvent<String>> transactionEvents = transactionsSink.asFlux()
                .map(txns -> ServerSentEvent.<String>builder().event("transactions")
                        .data(toJson(txns)).build());
        Flux<ServerSentEvent<String>> caseEvents = casesSink.asFlux()
                .map(c -> ServerSentEvent.<String>builder().event("case")
                        .data(toJson(c)).build());
        Flux<ServerSentEvent<String>> approvalEvents = approvalsSink.asFlux()
                .map(a -> ServerSentEvent.<String>builder().event("approval")
                        .data(toJson(a)).build());
        Flux<ServerSentEvent<String>> citationEvents = citationsSink.asFlux()
                .map(c -> ServerSentEvent.<String>builder().event("citations")
                        .data(toJson(c)).build());
        Flux<ServerSentEvent<String>> ledgerEvents = ledgerSink.asFlux()
                .map(entries -> ServerSentEvent.<String>builder().event("ledger")
                        .data(toJson(entries)).build());
        Flux<ServerSentEvent<String>> profileEvents = profileSink.asFlux()
                .map(pr -> ServerSentEvent.<String>builder().event("profile")
                        .data(toJson(pr)).build());
        Flux<ServerSentEvent<String>> statementEvents = statementSink.asFlux()
                .map(s -> ServerSentEvent.<String>builder().event("statement")
                        .data(toJson(s)).build());
        answer = answer.doFinally(sig -> {
            statusSink.tryEmitComplete();
            cardsSink.tryEmitComplete();
            accountsSink.tryEmitComplete();
            transactionsSink.tryEmitComplete();
            casesSink.tryEmitComplete();
            approvalsSink.tryEmitComplete();
            citationsSink.tryEmitComplete();
            ledgerSink.tryEmitComplete();
            profileSink.tryEmitComplete();
            statementSink.tryEmitComplete();
        });

        // If the breaker is open or the stream times out, tell the user cleanly instead
        // of dropping a half-rendered answer.
        return Flux.merge(answer, statusEvents, cardEvents, accountEvents, transactionEvents, caseEvents,
                        approvalEvents, citationEvents, ledgerEvents, profileEvents, statementEvents)
                .onErrorResume(err -> {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String msg = err instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException
                    || err instanceof io.github.resilience4j.bulkhead.BulkheadFullException
                    ? "The assistant is busy right now. Please retry in a moment."
                    : "That request took too long. Please try again.";
            audit.record(tenantId, userId, cid, "unavailable", ms, 0, redactedQ);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("token").data(toJson(Map.of("t", msg))).build(),
                    metaEvent(new ChatReply(cid, msg, "unavailable", ms, null, null)));
        });
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** One widget's full wiring: emit to the live SSE sink AND persist for history replay,
        as a single Consumer a tool's toolContext entry can call — see the sinks built at the
        top of {@link #stream}. */
    private <T> java.util.function.Consumer<T> widgetChannel(
            Sinks.Many<T> sink, String memoryKey, int turnSeq, String widgetType) {
        return value -> {
            sink.tryEmitNext(value);
            widgetHistoryStore.save(memoryKey, turnSeq, widgetType, toJson(value));
        };
    }

    /**
     * Index just past the last "safe to flush" point in the buffer: a newline, or a
     * sentence-ending . ? ! followed by a space (or end). PII patterns (card, SSN, IBAN,
     * email) never contain these, so a segment ending here can be fully redacted with no
     * risk of a token being split across the boundary. Returns -1 if none yet.
     */
    private static int lastSafeBoundary(CharSequence s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\n') return i + 1;
            if ((c == '.' || c == '?' || c == '!')
                    && (i + 1 >= s.length() || s.charAt(i + 1) == ' ')) return i + 1;
        }
        return -1;
    }

    /** Buffers a token Flux into the final answer text, stripping &lt;think&gt;…&lt;/think&gt; spans
        (defence-in-depth on top of aegis.llm.think=false for qwen3.5) across the whole stream —
        the same job ThinkTagFilter does incrementally, just resolved after collection. */
    private static Mono<String> collectAnswer(Flux<String> tokens) {
        ThinkTagFilter filter = new ThinkTagFilter();
        return tokens.collectList().map(chunks -> {
            StringBuilder sb = new StringBuilder();
            for (String raw : chunks) sb.append(filter.accept(raw));
            sb.append(filter.flush());
            return sb.toString();
        });
    }

    /** A well-formed reply ends in terminal punctuation or a closing delimiter. Anything else
        (mid-word, a dangling "and", a stray digit) is the signature of the Spring AI streaming
        truncation bug (see the call site in {@link #stream}) — worth one retry. */
    private static boolean looksTruncated(String answer) {
        if (answer == null) return true;
        String t = answer.strip();
        if (t.isEmpty()) return true;
        char last = t.charAt(t.length() - 1);
        return "!?.\"')]}”’".indexOf(last) < 0;
    }

    /** A leaked tool-call line is dropped; PII in a line is redacted; then it's an SSE token. */
    private ServerSentEvent<String> tokenEvent(String line) {
        String safe = GuardrailAdvisor.looksLikeLeakedToolCall(line) ? "" : piiRedactor.redact(line);
        return ServerSentEvent.<String>builder().event("token").data(toJson(Map.of("t", safe))).build();
    }

    private ServerSentEvent<String> metaEvent(ChatReply reply) {
        return ServerSentEvent.<String>builder().event("meta").data(toJson(reply)).build();
    }

    /** Emit a full answer as a single token event + meta (cache hit or a blocked/refusal path). */
    private Flux<ServerSentEvent<String>> oneShot(String cid, String answer, String source,
                                                  long start, SemanticCache.Hit hit) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        ChatReply reply = hit != null
                ? ChatReply.fromCache(cid, hit, ms)
                : new ChatReply(cid, answer, source, ms, null, null);
        return Flux.just(
                ServerSentEvent.<String>builder().event("token").data(toJson(Map.of("t", answer))).build(),
                metaEvent(reply));
    }

    /** Don't cache dynamic (account/action) answers, refusals, or low-confidence "couldn't find" replies. */
    private static boolean isSafeToCache(String answer, boolean dynamic) {
        if (dynamic || answer == null) return false;
        return !AnswerConfidence.looksLowConfidence(answer);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
