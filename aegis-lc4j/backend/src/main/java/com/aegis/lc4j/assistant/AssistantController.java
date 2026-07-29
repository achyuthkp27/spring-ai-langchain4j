package com.aegis.lc4j.assistant;

import com.aegis.lc4j.admin.AuditTrail;
import com.aegis.lc4j.guardrails.BudgetGuard;
import com.aegis.lc4j.guardrails.InjectionScreen;
import com.aegis.lc4j.guardrails.PiiRedactor;
import com.aegis.lc4j.guardrails.RateLimiter;
import com.aegis.lc4j.guardrails.TokenAudit;
import com.aegis.lc4j.memory.PgChatMemoryStore;
import com.aegis.lc4j.rag.SemanticCache;
import com.aegis.lc4j.resilience.LlmResilience;
import com.aegis.lc4j.security.CurrentUser;
import com.aegis.lc4j.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);
    private static final String REFUSAL = "Request blocked by Aegis guardrails.";
    private static final long SSE_TIMEOUT_MS = 120_000;

    private final AssistantService assistantService;
    private final PgChatMemoryStore memoryStore;
    private final SemanticCache semanticCache;
    private final RateLimiter rateLimiter;
    private final BudgetGuard budgetGuard;
    private final InjectionScreen injectionScreen;
    private final PiiRedactor piiRedactor;
    private final ScopeGate scopeGate;
    private final LlmResilience resilience;
    private final AuditTrail audit;
    private final TokenAudit tokenAudit;
    private final ObjectMapper json = new ObjectMapper();

    private final ExecutorService pipeline = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "assistant-pipeline");
        t.setDaemon(true);
        return t;
    });

    public AssistantController(AssistantService assistantService, PgChatMemoryStore memoryStore,
                               SemanticCache semanticCache, RateLimiter rateLimiter,
                               BudgetGuard budgetGuard, InjectionScreen injectionScreen,
                               PiiRedactor piiRedactor, ScopeGate scopeGate,
                               LlmResilience resilience, AuditTrail audit, TokenAudit tokenAudit) {
        this.assistantService = assistantService;
        this.memoryStore = memoryStore;
        this.semanticCache = semanticCache;
        this.rateLimiter = rateLimiter;
        this.budgetGuard = budgetGuard;
        this.injectionScreen = injectionScreen;
        this.piiRedactor = piiRedactor;
        this.scopeGate = scopeGate;
        this.resilience = resilience;
        this.audit = audit;
        this.tokenAudit = tokenAudit;
    }

    public record ChatRequest(String conversationId, String message) {
        public ChatRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
        }
    }

    public record ChatReply(String conversationId, String answer, String source,
                            long elapsedMs, Double similarity, String matchedQuestion) {
    }

    @GetMapping("/history")
    public List<Map<String, String>> history(@RequestParam(defaultValue = "default") String conversationId) {
        Principal principal = CurrentUser.get();
        List<ChatMessage> messages = memoryStore.getMessages(
                AssistantService.memoryKey(principal, conversationId));
        List<Map<String, String>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage um && um.hasSingleText()) {
                out.add(Map.of("role", "user", "text", um.singleText()));
            } else if (m instanceof AiMessage am && am.text() != null && !am.text().isBlank()) {
                out.add(Map.of("role", "assistant", "text", am.text()));
            }
        }
        return out;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        long start = System.nanoTime();
        
        Principal principal = CurrentUser.get();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        pipeline.submit(() -> run(emitter, principal, request, start));
        return emitter;
    }

    private void run(SseEmitter emitter, Principal principal, ChatRequest request, long start) {
        String tenantId = principal.tenantId();
        String userId = principal.userId();
        String cid = request.conversationId();
        String memoryKey = AssistantService.memoryKey(principal, cid);
        String redactedQ = piiRedactor.redact(request.message());

        try {
            
            if (!rateLimiter.allow(tenantId)) {
                audit.record(tenantId, userId, cid, "blocked-rate", ms(start), 0, redactedQ);
                oneShot(emitter, cid, "You're sending requests too quickly. Please slow down and retry.",
                        "blocked", start, null);
                return;
            }
            try {
                budgetGuard.checkOrThrow(tenantId);
            } catch (BudgetGuard.BudgetExceededException e) {
                audit.record(tenantId, userId, cid, "blocked-budget", ms(start), 0, redactedQ);
                oneShot(emitter, cid, "Today's usage limit has been reached. Please try again tomorrow.",
                        "blocked", start, null);
                return;
            }
            if (injectionScreen.screen(request.message()).flagged()) {
                audit.record(tenantId, userId, cid, "blocked-injection", ms(start), 0, redactedQ);
                oneShot(emitter, cid, REFUSAL + " (suspected prompt injection)", "blocked", start, null);
                return;
            }

            var hit = semanticCache.lookup(tenantId, request.message());
            if (hit.isPresent()) {
                audit.record(tenantId, userId, cid, "cache", ms(start), hit.get().answer().length(), redactedQ);
                oneShot(emitter, cid, hit.get().answer(), "cache", start, hit.get());
                return;
            }

            if (!scopeGate.inScope(request.message(), memoryKey)) {
                audit.record(tenantId, userId, cid, "blocked-scope", ms(start), 0, redactedQ);
                oneShot(emitter, cid, ScopeGate.REDIRECT, "blocked", start, null);
                return;
            }

            streamModel(emitter, principal, request, start, memoryKey, redactedQ);
        } catch (LlmResilience.LlmUnavailableException e) {
            unavailable(emitter, principal, cid, start, redactedQ, e.getMessage());
        } catch (Exception e) {
            log.error("assistant.pipeline.error", e);
            unavailable(emitter, principal, cid, start, redactedQ, LlmResilience.TIMEOUT_MESSAGE);
        }
    }

    private void streamModel(SseEmitter emitter, Principal principal, ChatRequest request,
                             long start, String memoryKey, String redactedQ) {
        String tenantId = principal.tenantId();
        String userId = principal.userId();
        String cid = request.conversationId();

        AtomicBoolean dynamic = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        StringBuilder full = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        ThinkTagFilter thinkFilter = new ThinkTagFilter();

        LlmResilience.StreamCall call = resilience.startStreamCall(() -> {
            if (finished.compareAndSet(false, true)) {
                unavailable(emitter, principal, cid, start, redactedQ, LlmResilience.TIMEOUT_MESSAGE);
            }
        });

        Consumer<String> statusSink = s -> {
            call.tick();
            if (!finished.get()) {
                sendQuietly(emitter, "status", Map.of("s", s));
            }
        };

        TokenStream stream = assistantService.chat(principal, cid, redactedQ, dynamic, statusSink);

        stream.onPartialResponse(chunk -> {
            call.tick();
            if (finished.get()) return;
            String visible = thinkFilter.accept(chunk);
            if (visible.isEmpty()) return;
            full.append(visible);
            pending.append(visible);

            int b = lastSafeBoundary(pending);
            if (b > 0) {
                String segment = pending.substring(0, b);
                pending.delete(0, b);
                sendToken(emitter, segment);
            }
        });

        stream.onCompleteResponse(response -> {
            if (!finished.compareAndSet(false, true)) return;
            call.success();
            String flushed = thinkFilter.flush();
            String tail = pending.toString() + flushed;
            if (!tail.isEmpty()) {
                full.append(flushed);
                sendToken(emitter, tail);
            }

            String answer = piiRedactor.redact(full.toString());
            if (isSafeToCache(answer, dynamic.get())) {
                semanticCache.put(tenantId, request.message(), answer);
            }
            long in = 0, out = 0;
            var usage = response.tokenUsage();
            if (usage != null) {
                in = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
                out = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            }
            
            budgetGuard.record(tenantId, usage != null ? in + out : Math.max(1, answer.length() / 4));
            long elapsed = ms(start);
            tokenAudit.record(tenantId, "llm", Duration.ofMillis(elapsed), in, out);
            audit.record(tenantId, userId, cid, "llm", elapsed, answer.length(), redactedQ);
            sendQuietly(emitter, "meta", new ChatReply(cid, answer, "llm", elapsed, null, null));
            emitter.complete();
        });

        stream.onError(err -> {
            if (!finished.compareAndSet(false, true)) return;
            call.failure(err);
            log.warn("assistant.stream.error {}", err.toString());
            String msg = err instanceof com.aegis.lc4j.security.AccessDeniedException
                    ? "I couldn't do that: " + err.getMessage()
                    : LlmResilience.TIMEOUT_MESSAGE;
            audit.record(tenantId, userId, cid, "error", ms(start), 0, redactedQ);
            sendQuietly(emitter, "token", Map.of("t", msg));
            sendQuietly(emitter, "meta", new ChatReply(cid, msg, "unavailable", ms(start), null, null));
            emitter.complete();
        });

        stream.start();
    }

    private void unavailable(SseEmitter emitter, Principal p, String cid, long start,
                             String redactedQ, String message) {
        audit.record(p.tenantId(), p.userId(), cid, "unavailable", ms(start), 0, redactedQ);
        sendQuietly(emitter, "token", Map.of("t", message));
        sendQuietly(emitter, "meta", new ChatReply(cid, message, "unavailable", ms(start), null, null));
        emitter.complete();
    }

    private void oneShot(SseEmitter emitter, String cid, String answer, String source,
                         long start, SemanticCache.Hit hit) {
        long elapsed = ms(start);
        ChatReply reply = hit != null
                ? new ChatReply(cid, hit.answer(), "cache", elapsed, hit.similarity(), hit.matchedQuestion())
                : new ChatReply(cid, answer, source, elapsed, null, null);
        sendQuietly(emitter, "token", Map.of("t", answer));
        sendQuietly(emitter, "meta", reply);
        emitter.complete();
    }

    private void sendToken(SseEmitter emitter, String segment) {
        String safe = looksLikeLeakedToolCall(segment) ? "" : piiRedactor.redact(segment);
        if (!safe.isEmpty()) {
            sendQuietly(emitter, "token", Map.of("t", safe));
        }
    }

    private void sendQuietly(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(toJson(payload), MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            
            log.debug("sse.send.failed {}", e.getMessage());
        }
    }

    static boolean looksLikeLeakedToolCall(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (!t.contains("{") || !t.contains("}")) return false;
        return t.contains("\"name\"") && (t.contains("\"parameters\"") || t.contains("\"arguments\""));
    }

    static int lastSafeBoundary(CharSequence s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\n') return i + 1;
            if ((c == '.' || c == '?' || c == '!')
                    && (i + 1 >= s.length() || s.charAt(i + 1) == ' ')) return i + 1;
        }
        return -1;
    }

    private static boolean isSafeToCache(String answer, boolean dynamic) {
        if (dynamic || answer == null) return false;
        String a = answer.toLowerCase();
        return !a.contains("i don't have")
                && !a.contains(REFUSAL.toLowerCase())
                && !a.contains("couldn't find")
                && !a.contains("could not find")
                && !a.contains("i'm not sure");
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
