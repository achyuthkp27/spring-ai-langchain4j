package com.aegis.merged.guardrails;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class TokenAuditAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenAuditAdvisor.class);

    private final MeterRegistry meterRegistry;

    public TokenAuditAdvisor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        var chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null) {
            Usage usage = chatResponse.getMetadata().getUsage();
            String model = chatResponse.getMetadata().getModel();
            String tenant = tenantOf(request);
            log.info("ai.call model={} tenant={} elapsedMs={} promptTokens={} completionTokens={} totalTokens={}",
                    model, tenant, elapsedMs,
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            
            meterRegistry.counter("aegis.ai.tokens.total", "tenant", tenant, "model", safe(model))
                    .increment(usage.getTotalTokens());
            meterRegistry.counter("aegis.ai.calls.total", "tenant", tenant, "model", safe(model))
                    .increment();
        } else {
            log.info("ai.call elapsedMs={} (no usage metadata)", elapsedMs);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.nanoTime();

        var lastUsage = new java.util.concurrent.atomic.AtomicReference<Usage>();
        var model = new java.util.concurrent.atomic.AtomicReference<>("unknown");
        return chain.nextStream(request)
                .doOnNext(r -> {
                    var cr = r.chatResponse();
                    if (cr == null || cr.getMetadata() == null) return;
                    Usage u = cr.getMetadata().getUsage();
                    if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                        lastUsage.set(u);
                    }
                    String m = cr.getMetadata().getModel();
                    if (m != null && !m.isBlank()) model.set(m);
                })
                .doOnComplete(() -> {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    Usage usage = lastUsage.get();
                    String tenant = tenantOf(request);
                    if (usage != null) {
                        log.info("ai.stream model={} tenant={} elapsedMs={} promptTokens={} completionTokens={} totalTokens={}",
                                model.get(), tenant, elapsedMs,
                                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
                        meterRegistry.counter("aegis.ai.tokens.total", "tenant", tenant, "model", model.get())
                                .increment(usage.getTotalTokens());
                        meterRegistry.counter("aegis.ai.calls.total", "tenant", tenant, "model", model.get())
                                .increment();
                    } else {
                        log.info("ai.stream elapsedMs={} (no usage metadata)", elapsedMs);
                    }
                });
    }

    private String tenantOf(ChatClientRequest request) {
        Object t = request.context().get(GuardrailAdvisor.TENANT_PARAM);
        return t != null ? t.toString() : "default";
    }

    private String safe(String s) {
        return s != null ? s : "unknown";
    }

    @Override
    public String getName() {
        return "token-audit";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
