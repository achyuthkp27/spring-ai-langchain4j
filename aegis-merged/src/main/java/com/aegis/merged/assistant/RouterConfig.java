package com.aegis.merged.assistant;

import com.aegis.merged.guardrails.GuardrailAdvisor;
import com.aegis.merged.guardrails.TokenAuditAdvisor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The escalation tier of {@link ModelRouter}'s cascade: an explicit, second ChatClient bean
 * independent of whichever provider {@code spring.ai.model.chat} auto-selected — the same
 * "build it explicitly, don't rely on the one auto-configured bean" approach
 * {@link com.aegis.merged.config.CacheConfig} uses for the semantic-cache embedding model.
 *
 * Absent (aegis.router.enabled=false, or a misconfigured provider), no bean is registered here
 * and {@link com.aegis.merged.assistant.AssistantController} falls back to the primary
 * assistant client for every request — escalation is strictly additive.
 */
@Configuration
@ConditionalOnProperty(prefix = "aegis.router", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RouterConfig {

    @Bean
    @Qualifier("escalationClient")
    @ConditionalOnProperty(prefix = "aegis.router.escalation", name = "provider",
            havingValue = "ollama", matchIfMissing = true)
    ChatClient ollamaEscalationClient(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${aegis.router.escalation.model:qwen3.5:9b}") String model,
            @Value("${aegis.llm.think:}") String think,
            ChatMemory chatMemory, TokenAuditAdvisor tokenAudit, GuardrailAdvisor guardrails) {

        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        var options = OllamaChatOptions.builder().model(model);
        if (!think.isBlank()) {
            options.thinkOption(new ThinkOption.ThinkBoolean(Boolean.parseBoolean(think)));
        }
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options.build())
                .build();
        // Same advisor chain as the primary assistantClient (AssistantConfig) — a bug fixed
        // after a live escalation lost the whole conversation ("this is our first message")
        // because this bean never had MessageChatMemoryAdvisor wired in: escalating to the
        // bigger model isn't supposed to also silently drop history, guardrails, and audit.
        return ChatClient.builder(chatModel)
                .defaultSystem(AssistantConfig.ASSISTANT_SYSTEM_PROMPT)
                .defaultAdvisors(tokenAudit, guardrails, MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    @Qualifier("escalationClient")
    @ConditionalOnProperty(prefix = "aegis.router.escalation", name = "provider", havingValue = "anthropic")
    ChatClient anthropicEscalationClient(
            @Value("${anthropic.api-key:${ANTHROPIC_API_KEY:}}") String apiKey,
            @Value("${aegis.router.escalation.model:claude-sonnet-4-5}") String model,
            ChatMemory chatMemory, TokenAuditAdvisor tokenAudit, GuardrailAdvisor guardrails) {

        AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).build();
        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder().model(model).maxTokens(1024).build())
                .build();
        return ChatClient.builder(chatModel)
                .defaultSystem(AssistantConfig.ASSISTANT_SYSTEM_PROMPT)
                .defaultAdvisors(tokenAudit, guardrails, MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
