package com.aegis.merged.assistant;

import com.aegis.merged.admin.AuditTrail;
import com.aegis.merged.guardrails.BudgetGuard;
import com.aegis.merged.guardrails.GuardrailAdvisor;
import com.aegis.merged.guardrails.InjectionScreen;
import com.aegis.merged.guardrails.LlmGuard;
import com.aegis.merged.guardrails.PiiRedactor;
import com.aegis.merged.guardrails.RateLimiter;
import com.aegis.merged.rag.SemanticCache;
import com.aegis.merged.security.AccessDeniedException;
import com.aegis.merged.security.CurrentUser;
import com.aegis.merged.security.Principal;
import com.aegis.merged.tools.BankingTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final ChatClient assistant;
    private final BankingTools bankingTools;
    private final PolicySearchTool policySearchTool;
    private final SemanticCache semanticCache;

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

    public record WidgetEvent(String type, com.fasterxml.jackson.databind.JsonNode payload) {
    }

    public record HistoryMessage(String role, String text, List<WidgetEvent> widgets) {
    }

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

        long assistantCountInWindow = messages.stream()
                .filter(m -> m.getMessageType() == MessageType.ASSISTANT)
                .count();
        int assistantSeq = widgetHistoryStore.currentTurnSeq(memoryKey) - (int) assistantCountInWindow;

        List<HistoryMessage> out = new ArrayList<>();
        for (var m : messages) {
            var type = m.getMessageType();
            if (type == MessageType.USER) {
                out.add(new HistoryMessage("user", m.getText() == null ? "" : m.getText(), List.of()));
            } else if (type == MessageType.ASSISTANT) {
                assistantSeq++;
                List<WidgetEvent> widgets = new ArrayList<>();
                for (var row : byTurn.getOrDefault(assistantSeq, List.of())) {
                    try {
                        widgets.add(new WidgetEvent(row.widgetType(), json.readTree(row.payload())));
                    } catch (Exception e) {
                        
                    }
                }
                out.add(new HistoryMessage("assistant", m.getText() == null ? "" : m.getText(), widgets));
            }
        }
        return out;
    }

    @DeleteMapping("/history")
    public Map<String, String> deleteHistory(@RequestParam(defaultValue = "default") String conversationId) {
        Principal principal = CurrentUser.get();
        String memoryKey = principal.tenantId() + ":" + principal.userId() + ":" + conversationId;
        chatMemory.clear(memoryKey);
        widgetHistoryStore.deleteConversation(memoryKey);
        return Map.of("status", "deleted");
    }

    public record ChatRequest(String conversationId, String message) {

        private static final int MAX_MESSAGE_LENGTH = 4000;
        private static final int MAX_CONVERSATION_ID_LENGTH = 128;
        private static final java.util.regex.Pattern CONVERSATION_ID_PATTERN =
                java.util.regex.Pattern.compile("^[A-Za-z0-9._-]+$");

        public ChatRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
            if (conversationId.length() > MAX_CONVERSATION_ID_LENGTH
                    || !CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
                throw new IllegalArgumentException(
                        "conversationId must be alphanumeric (._- allowed), max "
                                + MAX_CONVERSATION_ID_LENGTH + " characters.");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank.");
            }
            if (message.length() > MAX_MESSAGE_LENGTH) {
                throw new IllegalArgumentException(
                        "message must be " + MAX_MESSAGE_LENGTH + " characters or fewer.");
            }
        }
    }

    public record ChatReply(String conversationId, String answer, String source,
                            long elapsedMs, Double similarity, String matchedQuestion) {
        static ChatReply fromCache(String cid, SemanticCache.Hit hit, long ms) {
            return new ChatReply(cid, hit.answer(), "cache", ms, hit.similarity(), hit.matchedQuestion());
        }
        static ChatReply fromLlm(String cid, String answer, long ms) {
            return new ChatReply(cid, answer, "llm", ms, null, null);
        }
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        long start = System.nanoTime();
        Principal principal = CurrentUser.get();
        String tenantId = principal.tenantId();
        String userId = principal.userId();
        String cid = request.conversationId();
        String memoryKey = tenantId + ":" + userId + ":" + cid;

        String redactedQ = piiRedactor.redact(request.message());

        if (!rateLimiter.allow(tenantId, userId)) {
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

        java.util.Optional<SemanticCache.Hit> hit;
        try {
            hit = semanticCache.lookup(tenantId, request.message());
        } catch (Exception e) {
            log.warn("semanticCache.lookup.failed tenant={} err={}", tenantId, e.toString());
            hit = java.util.Optional.empty();
        }
        if (hit.isPresent()) {
            audit.record(tenantId, userId, cid, "cache", ms(start), hit.get().answer().length(), redactedQ);
            return oneShot(cid, hit.get().answer(), "cache", start, hit.get());
        }

        if (!scopeGate.inScope(request.message(), memoryKey)) {
            audit.record(tenantId, userId, cid, "blocked-scope", ms(start), 0, redactedQ);
            return oneShot(cid, ScopeGate.REDIRECT, "blocked", start, null);
        }

        String redactedInput = redactedQ;
        AtomicBoolean dynamic = new AtomicBoolean(false);
        AtomicBoolean toolFailed = new AtomicBoolean(false);

        AtomicBoolean mutated = new AtomicBoolean(false);

        Sinks.Many<String> statusSink = Sinks.many().unicast().onBackpressureBuffer();
        java.util.function.Consumer<String> statusFn = statusSink::tryEmitNext;

        int turnSeq = widgetHistoryStore.nextTurnSeq(memoryKey);
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.Card>> cardsSink = Sinks.many().unicast().onBackpressureBuffer();
        var cardsFn = widgetChannel(cardsSink, memoryKey, turnSeq, "cards");
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.Account>> accountsSink = Sinks.many().unicast().onBackpressureBuffer();
        var accountsFn = widgetChannel(accountsSink, memoryKey, turnSeq, "accounts");
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.Transaction>> transactionsSink = Sinks.many().unicast().onBackpressureBuffer();
        var transactionsFn = widgetChannel(transactionsSink, memoryKey, turnSeq, "transactions");
        Sinks.Many<com.aegis.merged.domain.BankingService.DisputeCase> casesSink = Sinks.many().unicast().onBackpressureBuffer();
        var casesFn = widgetChannel(casesSink, memoryKey, turnSeq, "case");
        Sinks.Many<com.aegis.merged.domain.BankingService.Approval> approvalsSink = Sinks.many().unicast().onBackpressureBuffer();
        var approvalsFn = widgetChannel(approvalsSink, memoryKey, turnSeq, "approval");
        Sinks.Many<java.util.List<BankingTools.Citation>> citationsSink = Sinks.many().unicast().onBackpressureBuffer();
        var citationsFn = widgetChannel(citationsSink, memoryKey, turnSeq, "citations");
        Sinks.Many<java.util.List<com.aegis.merged.domain.BankingService.LedgerEntry>> ledgerSink = Sinks.many().unicast().onBackpressureBuffer();
        var ledgerFn = widgetChannel(ledgerSink, memoryKey, turnSeq, "ledger");
        Sinks.Many<com.aegis.merged.domain.BankingService.CustomerProfile> profileSink = Sinks.many().unicast().onBackpressureBuffer();
        var profileFn = widgetChannel(profileSink, memoryKey, turnSeq, "profile");
        Sinks.Many<BankingTools.SpendingSummary> statementSink = Sinks.many().unicast().onBackpressureBuffer();
        var statementFn = widgetChannel(statementSink, memoryKey, turnSeq, "statement");

        ModelRouter.Tier tier = router.decide(request.message(), memoryKey);
        ChatClient model = tier == ModelRouter.Tier.COMPLEX
                ? escalationClient.getIfAvailable(() -> assistant)
                : assistant;
        if (model != assistant) {
            statusFn.accept("Using extended reasoning…");
        }

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

        java.util.function.Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>> callModel =
                () -> llmGuard.guard(model.prompt()
                .system(sp -> sp.param("bankName", TenantNames.displayName(tenantId)))
                .user(redactedInput)
                .tools(bankingTools, policySearchTool)
                .toolContext(toolContext)
                .advisors(a -> a
                        .param(ChatMemory.CONVERSATION_ID, memoryKey)
                        .param(GuardrailAdvisor.TENANT_PARAM, tenantId))
                .stream()
                .chatResponse());

        Mono<CollectedAnswer> firstAttempt = collectAnswer(callModel.get());
        Mono<CollectedAnswer> finalAnswer = firstAttempt.flatMap(first -> {
            if (!looksTruncated(first.text())) return Mono.just(first);
            if (mutated.get()) {

                log.warn("assistant.answer.looksTruncated NOT retrying (mutating tool already ran) "
                        + "key={} chars={}", memoryKey, first.text().length());
                return Mono.just(first);
            }
            log.warn("assistant.answer.looksTruncated retrying once key={} chars={}", memoryKey, first.text().length());
            return collectAnswer(callModel.get())
                    .map(retry -> looksTruncated(retry.text()) && retry.text().length() <= first.text().length()
                            ? first : retry)
                    .onErrorReturn(first);
        });

        Flux<ServerSentEvent<String>> answer = finalAnswer.flatMapMany(collected -> {
            String rawFull = collected.text();
            List<ServerSentEvent<String>> out = new ArrayList<>();

            StringBuilder sanitized = new StringBuilder();
            String remaining = rawFull;
            while (true) {

                int b = lastSafeBoundary(remaining);
                if (b <= 0) break;
                String segment = remaining.substring(0, b);
                if (!GuardrailAdvisor.looksLikeLeakedToolCall(segment)) sanitized.append(segment);
                out.add(tokenEvent(segment));   
                remaining = remaining.substring(b);
            }
            if (!remaining.isEmpty()) {
                if (!GuardrailAdvisor.looksLikeLeakedToolCall(remaining)) sanitized.append(remaining);
                out.add(tokenEvent(remaining));
            }

            String redactedAnswer = piiRedactor.redact(sanitized.toString());
            if (isSafeToCache(redactedAnswer, dynamic.get())) {
                semanticCache.put(tenantId, request.message(), redactedAnswer);
            }
            router.markLowConfidence(memoryKey, redactedAnswer, toolFailed.get());

            var usage = collected.usage();
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                budgetGuard.record(tenantId, usage.getTotalTokens());
            } else {
                budgetGuard.record(tenantId, Math.max(1, redactedAnswer.length() / 4));
            }
            long ms = ms(start);
            audit.record(tenantId, userId, cid, "llm", ms, redactedAnswer.length(), redactedQ);
            out.add(metaEvent(new ChatReply(cid, redactedAnswer, "llm", ms, null, null)));
            return Flux.fromIterable(out);
        });

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

        return Flux.merge(answer, statusEvents, cardEvents, accountEvents, transactionEvents, caseEvents,
                        approvalEvents, citationEvents, ledgerEvents, profileEvents, statementEvents)
                .onErrorResume(err -> {
            long ms = (System.nanoTime() - start) / 1_000_000;

            boolean denied = err instanceof AccessDeniedException
                    || err.getCause() instanceof AccessDeniedException;
            boolean busy = err instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException
                    || err instanceof io.github.resilience4j.bulkhead.BulkheadFullException;
            String source = denied ? "denied" : "unavailable";
            String msg = denied
                    ? "I can't do that — it isn't one of your own accounts or cards."
                    : busy ? "The assistant is busy right now. Please retry in a moment."
                            : "That request took too long. Please try again.";
            audit.record(tenantId, userId, cid, source, ms, 0, redactedQ);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("token").data(toJson(Map.of("t", msg))).build(),
                    metaEvent(new ChatReply(cid, msg, source, ms, null, null)));
        });
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private <T> java.util.function.Consumer<T> widgetChannel(
            Sinks.Many<T> sink, String memoryKey, int turnSeq, String widgetType) {
        return value -> {
            sink.tryEmitNext(value);
            widgetHistoryStore.save(memoryKey, turnSeq, widgetType, toJson(value));
        };
    }

    private static int lastSafeBoundary(CharSequence s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\n') return i + 1;
            if ((c == '.' || c == '?' || c == '!')
                    && (i + 1 >= s.length() || s.charAt(i + 1) == ' ')) return i + 1;
        }
        return -1;
    }

    private record CollectedAnswer(String text, org.springframework.ai.chat.metadata.Usage usage) {
    }

    private static Mono<CollectedAnswer> collectAnswer(Flux<org.springframework.ai.chat.model.ChatResponse> responses) {
        ThinkTagFilter filter = new ThinkTagFilter();
        var lastUsage = new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage>();
        return responses
                .doOnNext(cr -> {
                    if (cr.getMetadata() == null) return;
                    var u = cr.getMetadata().getUsage();
                    if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) lastUsage.set(u);
                })
                .map(cr -> cr.getResult() != null && cr.getResult().getOutput() != null
                        ? cr.getResult().getOutput().getText() : null)
                .collectList()
                .map(chunks -> {
                    StringBuilder sb = new StringBuilder();
                    for (String raw : chunks) if (raw != null) sb.append(filter.accept(raw));
                    sb.append(filter.flush());
                    return new CollectedAnswer(sb.toString(), lastUsage.get());
                });
    }

    private static boolean looksTruncated(String answer) {
        if (answer == null) return true;
        String t = answer.strip();
        if (t.isEmpty()) return true;
        char last = t.charAt(t.length() - 1);
        return "!?.\"')]}”’".indexOf(last) < 0;
    }

    private ServerSentEvent<String> tokenEvent(String line) {
        String safe = GuardrailAdvisor.looksLikeLeakedToolCall(line) ? "" : piiRedactor.redact(line);
        return ServerSentEvent.<String>builder().event("token").data(toJson(Map.of("t", safe))).build();
    }

    private ServerSentEvent<String> metaEvent(ChatReply reply) {
        return ServerSentEvent.<String>builder().event("meta").data(toJson(reply)).build();
    }

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
