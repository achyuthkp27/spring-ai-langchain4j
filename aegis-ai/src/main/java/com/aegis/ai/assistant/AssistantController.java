package com.aegis.ai.assistant;

import com.aegis.ai.admin.AuditTrail;
import com.aegis.ai.guardrails.BudgetGuard;
import com.aegis.ai.guardrails.GuardrailAdvisor;
import com.aegis.ai.guardrails.InjectionScreen;
import com.aegis.ai.guardrails.LlmGuard;
import com.aegis.ai.guardrails.PiiRedactor;
import com.aegis.ai.guardrails.RateLimiter;
import com.aegis.ai.rag.SemanticCache;
import com.aegis.ai.security.CurrentUser;
import com.aegis.ai.security.Principal;
import com.aegis.ai.tools.BankingTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
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
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.ai.chat.messages.MessageType;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

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
    private final ObjectMapper json = new ObjectMapper();

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
                              ChatMemory chatMemory) {
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
    }

    @GetMapping("/history")
    public List<Map<String, String>> history(@RequestParam(defaultValue = "default") String conversationId) {
        Principal principal = CurrentUser.get();
        String memoryKey = principal.tenantId() + ":" + principal.userId() + ":" + conversationId;
        var messages = chatMemory.get(memoryKey);
        if (messages == null) return List.of();
        return messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER
                          || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> Map.of(
                        "role", m.getMessageType() == MessageType.USER
                                ? "user" : "assistant",
                        "text", m.getText() == null ? "" : m.getText()))
                .toList();
    }

    public record ChatRequest(String conversationId, String message) {
        public ChatRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
        }
    }

    // source: "cache" = answered from semantic cache (~ms, no LLM);

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

        var hit = semanticCache.lookup(tenantId, request.message());
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
        StringBuilder full = new StringBuilder();
        StringBuilder pending = new StringBuilder();

        Sinks.Many<String> statusSink = Sinks.many().multicast().onBackpressureBuffer();
        Consumer<String> statusFn = statusSink::tryEmitNext;

        Flux<String> tokens = llmGuard.guard(assistant.prompt()
                .user(redactedInput)
                .tools(bankingTools, policySearchTool)
                .toolContext(Map.of(
                        BankingTools.PRINCIPAL_KEY, principal,
                        BankingTools.DYNAMIC_ACCESS_KEY, dynamic,
                        BankingTools.STATUS_KEY, statusFn))
                .advisors(a -> a
                        .param(ChatMemory.CONVERSATION_ID, memoryKey)
                        .param(GuardrailAdvisor.TENANT_PARAM, tenantId))
                .stream()
                .content());

        Flux<ServerSentEvent<String>> body = tokens.concatMap(chunk -> {
            full.append(chunk);
            pending.append(chunk);

            int b = lastSafeBoundary(pending);
            if (b <= 0) return Flux.empty();
            String segment = pending.substring(0, b);
            pending.delete(0, b);
            return Flux.just(tokenEvent(segment));   
        });

        Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
            List<ServerSentEvent<String>> out = new ArrayList<>();
            if (pending.length() > 0) out.add(tokenEvent(pending.toString()));
            String answer = full.toString();
            if (isSafeToCache(answer, dynamic.get())) {
                semanticCache.put(tenantId, request.message(), answer);
            }

            budgetGuard.record(tenantId, Math.max(1, answer.length() / 4));
            long ms = (System.nanoTime() - start) / 1_000_000;
            audit.record(tenantId, userId, cid, "llm", ms, answer.length(), redactedQ);
            out.add(metaEvent(new ChatReply(cid, answer, "llm", ms, null, null)));
            return Flux.fromIterable(out);
        });

        Flux<ServerSentEvent<String>> statusEvents = statusSink.asFlux()
                .map(s -> ServerSentEvent.<String>builder().event("status")
                        .data(toJson(Map.of("s", s))).build());
        Flux<ServerSentEvent<String>> answer = body.concatWith(tail)
                .doFinally(sig -> statusSink.tryEmitComplete());

        return Flux.merge(answer, statusEvents).onErrorResume(err -> {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String msg = err instanceof CallNotPermittedException
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

    private static int lastSafeBoundary(CharSequence s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\n') return i + 1;
            if ((c == '.' || c == '?' || c == '!')
                    && (i + 1 >= s.length() || s.charAt(i + 1) == ' ')) return i + 1;
        }
        return -1;
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
        String a = answer.toLowerCase();
        return !a.contains("i don't have")
                && !a.contains(GuardrailAdvisor.REFUSAL.toLowerCase())
                && !a.contains("couldn't find")
                && !a.contains("could not find")
                && !a.contains("don't have that in the available documents")
                && !a.contains("i'm not sure");
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
