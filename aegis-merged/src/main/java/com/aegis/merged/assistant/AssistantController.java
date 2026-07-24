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

/**
 * The unified assistant: ONE endpoint for the user. The model orchestrates the
 * conversation and picks tools; the identity (from headers → a JWT in prod) is
 * passed via toolContext and drives all authorization and tenant scoping.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

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

    /**
     * Restore a conversation after a page refresh. The memory key is built from the
     * VERIFIED principal, so a user can only ever read their own conversations.
     */
    @GetMapping("/history")
    public List<Map<String, String>> history(@RequestParam(defaultValue = "default") String conversationId) {
        Principal principal = CurrentUser.get();
        String memoryKey = principal.tenantId() + ":" + principal.userId() + ":" + conversationId;
        var messages = chatMemory.get(memoryKey);
        if (messages == null) return List.of();
        return messages.stream()
                .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
                          || m.getMessageType() == org.springframework.ai.chat.messages.MessageType.ASSISTANT)
                .map(m -> Map.of(
                        "role", m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
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
        StringBuilder full = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        // Backported from the LC4j build: drop <think>…</think> reasoning spans
        // (defence-in-depth on top of aegis.llm.think=false for qwen3.5).
        ThinkTagFilter thinkFilter = new ThinkTagFilter();

        // Tool-progress side channel: tools push human-readable status lines here and
        // they egress immediately as SSE "status" events — the user sees "Searching
        // policy documents…" instead of a silent multi-second gap.
        Sinks.Many<String> statusSink = Sinks.many().multicast().onBackpressureBuffer();
        java.util.function.Consumer<String> statusFn = statusSink::tryEmitNext;

        // Guarded with the same circuit breaker + timeout as the blocking path.
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

        Flux<ServerSentEvent<String>> body = tokens.concatMap(rawChunk -> {
            String chunk = thinkFilter.accept(rawChunk);
            if (chunk.isEmpty()) return Flux.empty();
            full.append(chunk);
            pending.append(chunk);
            // Flush everything up to the last SAFE boundary (newline or sentence end).
            // These boundaries never fall inside a PAN/SSN/IBAN/email, so redacting the
            // flushed segment can't miss a token that's split across the boundary.
            int b = lastSafeBoundary(pending);
            if (b <= 0) return Flux.empty();
            String segment = pending.substring(0, b);
            pending.delete(0, b);
            return Flux.just(tokenEvent(segment));   // sanitized inside
        });

        // Terminal: flush the last partial line, cache if safe, emit meta.
        Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
            List<ServerSentEvent<String>> out = new ArrayList<>();
            String flushed = thinkFilter.flush();
            if (!flushed.isEmpty()) {
                full.append(flushed);
                pending.append(flushed);
            }
            if (pending.length() > 0) out.add(tokenEvent(pending.toString()));
            // Redact BEFORE caching / meta: token events are redacted per segment, but
            // `full` is the raw stream — a cache hit or meta replay must never leak PII.
            String answer = piiRedactor.redact(full.toString());
            if (isSafeToCache(answer, dynamic.get())) {
                semanticCache.put(tenantId, request.message(), answer);
            }
            // Rough token accounting so the budget guard still moves on the stream path
            // (streamed content() carries no usage metadata). ~4 chars/token heuristic.
            budgetGuard.record(tenantId, Math.max(1, answer.length() / 4));
            long ms = (System.nanoTime() - start) / 1_000_000;
            audit.record(tenantId, userId, cid, "llm", ms, answer.length(), redactedQ);
            out.add(metaEvent(new ChatReply(cid, answer, "llm", ms, null, null)));
            return Flux.fromIterable(out);
        });

        // Merge tool-progress events into the answer stream; close the side channel
        // when the main stream ends so the merged flux completes.
        Flux<ServerSentEvent<String>> statusEvents = statusSink.asFlux()
                .map(s -> ServerSentEvent.<String>builder().event("status")
                        .data(toJson(Map.of("s", s))).build());
        Flux<ServerSentEvent<String>> answer = body.concatWith(tail)
                .doFinally(sig -> statusSink.tryEmitComplete());

        // If the breaker is open or the stream times out, tell the user cleanly instead
        // of dropping a half-rendered answer.
        return Flux.merge(answer, statusEvents).onErrorResume(err -> {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String msg = err instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException
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
