package com.aegis.merged.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * The Achu FinBot guardrail chain, condensed into one ordered advisor for the demo:
 *   1. Budget check (LLM10) — deny before spending if the tenant is over quota.
 *   2. Injection screen (LLM01) — refuse override/exfiltration attempts.
 *   3. PII redaction (LLM02) — mask sensitive data before it reaches the model.
 * Runs after TokenAuditAdvisor (order 0); this sits at order 10.
 *
 * Tenant id is read from advisor params (key "tenantId"); absent → "default".
 */
@Component
public class GuardrailAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisor.class);
    public static final String TENANT_PARAM = "tenantId";
    public static final String REFUSAL = "Request blocked by Achu FinBot guardrails.";

    private final PiiRedactor pii;
    private final InjectionScreen injection;
    private final BudgetGuard budget;
    private final RateLimiter rateLimiter;

    public GuardrailAdvisor(PiiRedactor pii, InjectionScreen injection, BudgetGuard budget,
                            RateLimiter rateLimiter) {
        this.pii = pii;
        this.injection = injection;
        this.budget = budget;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String tenant = tenantOf(request);

        // 0. Rate limit (LLM10) — cheap first gate, before any spend.
        if (!rateLimiter.allow(tenant)) {
            log.warn("guardrail.ratelimit.denied tenant={}", tenant);
            return refusal(request, "You're sending requests too quickly. Please slow down and retry.");
        }

        // 1. Budget (LLM10)
        try {
            budget.checkOrThrow(tenant);
        } catch (BudgetGuard.BudgetExceededException e) {
            log.warn("guardrail.budget.denied tenant={}", tenant);
            return refusal(request, "Token budget exceeded for this tenant.");
        }

        String userText = lastUserText(request);

        // 2. Injection screen (LLM01)
        var screen = injection.screen(userText);
        if (screen.flagged()) {
            log.warn("guardrail.injection.blocked tenant={} pattern='{}'", tenant, screen.matched());
            return refusal(request, REFUSAL + " (suspected prompt injection)");
        }

        // 3. PII redaction (LLM02) — rewrite the user message before egress
        String redacted = pii.redact(userText);
        ChatClientRequest effective = request;
        if (!redacted.equals(userText)) {
            log.info("guardrail.pii.redacted tenant={}", tenant);
            effective = request.mutate()
                    .prompt(new Prompt(new UserMessage(redacted)))
                    .build();
        }

        ChatClientResponse response = chain.nextCall(effective);

        // Record usage against the budget for next time.
        var cr = response.chatResponse();
        if (cr != null && cr.getMetadata() != null && cr.getMetadata().getUsage() != null) {
            budget.record(tenant, cr.getMetadata().getUsage().getTotalTokens());
        }

        // OUTPUT GUARDRAILS (moderation on what the MODEL said, not just the user).
        if (cr != null && cr.getResult() != null && cr.getResult().getOutput() != null) {
            String out = cr.getResult().getOutput().getText();

            // a) Weak models sometimes emit a tool call as plain text — suppress it.
            if (looksLikeLeakedToolCall(out)) {
                log.warn("guardrail.output.leaked_tool_call tenant={} suppressed", tenant);
                return refusal(request,
                        "I wasn't able to complete that action just now. Could you rephrase, "
                        + "or ask me a specific question about a policy, account, or dispute?");
            }
            // b) Redact any PII the model itself produced (defence-in-depth on egress).
            String redactedOut = pii.redact(out);
            if (!redactedOut.equals(out)) {
                log.warn("guardrail.output.pii_redacted tenant={}", tenant);
                return refusal(request, redactedOut);
            }
        }
        return response;
    }

    /** True when the model's answer is (mostly) a raw tool-call JSON blob. */
    public static boolean looksLikeLeakedToolCall(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (!t.contains("{") || !t.contains("}")) return false;
        boolean hasName = t.contains("\"name\"");
        boolean hasArgs = t.contains("\"parameters\"") || t.contains("\"arguments\"");
        // JSON-ish object mentioning a call shape, and no substantial prose around it.
        return hasName && hasArgs;
    }

    private ChatClientResponse refusal(ChatClientRequest request, String message) {
        var assistant = new org.springframework.ai.chat.messages.AssistantMessage(message);
        var gen = new org.springframework.ai.chat.model.Generation(assistant);
        var chatResponse = new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(gen));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    private String tenantOf(ChatClientRequest request) {
        Object t = request.context().get(TENANT_PARAM);
        return t != null ? t.toString() : "default";
    }

    private String lastUserText(ChatClientRequest request) {
        var messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                return um.getText();
            }
        }
        return "";
    }

    @Override
    public String getName() {
        return "aegis-guardrails";
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
