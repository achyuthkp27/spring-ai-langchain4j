package com.aegis.merged.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GuardrailAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisor.class);
    public static final String TENANT_PARAM = "tenantId";
    
    public static final String USER_PARAM = "userId";
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
        String user = userOf(request);

        if (!rateLimiter.allow(tenant, user)) {
            log.warn("guardrail.ratelimit.denied tenant={} user={}", tenant, user);
            return refusal(request, "You're sending requests too quickly. Please slow down and retry.");
        }

        try {
            budget.checkOrThrow(tenant);
        } catch (BudgetGuard.BudgetExceededException e) {
            log.warn("guardrail.budget.denied tenant={}", tenant);
            return refusal(request, "Token budget exceeded for this tenant.");
        }

        String userText = lastUserText(request);

        var screen = injection.screen(userText);
        if (screen.flagged()) {
            log.warn("guardrail.injection.blocked tenant={} pattern='{}'", tenant, screen.matched());
            return refusal(request, REFUSAL + " (suspected prompt injection)");
        }

        String redacted = pii.redact(userText);
        ChatClientRequest effective = request;
        if (!redacted.equals(userText)) {
            log.info("guardrail.pii.redacted tenant={}", tenant);

            var msgs = new java.util.ArrayList<>(request.prompt().getInstructions());
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if (msgs.get(i) instanceof UserMessage) {
                    msgs.set(i, new UserMessage(redacted));
                    break;
                }
            }
            effective = request.mutate()
                    .prompt(new Prompt(msgs, request.prompt().getOptions()))
                    .build();
        }

        ChatClientResponse response = chain.nextCall(effective);

        var cr = response.chatResponse();
        if (cr != null && cr.getMetadata() != null && cr.getMetadata().getUsage() != null) {
            budget.record(tenant, cr.getMetadata().getUsage().getTotalTokens());
        }

        if (cr != null && cr.getResult() != null && cr.getResult().getOutput() != null) {
            String out = cr.getResult().getOutput().getText();

            if (looksLikeLeakedToolCall(out)) {
                log.warn("guardrail.output.leaked_tool_call tenant={} suppressed", tenant);
                return refusal(request,
                        "I wasn't able to complete that action just now. Could you rephrase, "
                        + "or ask me a specific question about a policy, account, or dispute?");
            }
            
            String redactedOut = pii.redact(out);
            if (!redactedOut.equals(out)) {
                log.warn("guardrail.output.pii_redacted tenant={}", tenant);
                return refusal(request, redactedOut);
            }
        }
        return response;
    }

    private static final java.util.regex.Pattern TOOL_CALL_SHAPE = java.util.regex.Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"(parameters|arguments)\"\\s*:\\s*\\{");

    public static boolean looksLikeLeakedToolCall(String text) {
        if (text == null) return false;
        return TOOL_CALL_SHAPE.matcher(text).find();
    }

    private ChatClientResponse refusal(ChatClientRequest request, String message) {
        var assistant = new AssistantMessage(message);
        var gen = new Generation(assistant);
        var chatResponse = new ChatResponse(List.of(gen));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    private String tenantOf(ChatClientRequest request) {
        Object t = request.context().get(TENANT_PARAM);
        return t != null ? t.toString() : "default";
    }

    private String userOf(ChatClientRequest request) {
        Object u = request.context().get(USER_PARAM);
        return u != null ? u.toString() : null;
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
