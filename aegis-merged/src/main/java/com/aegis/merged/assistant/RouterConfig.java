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

        var options = OllamaChatOptions.builder().model(model).numCtx(16384).numPredict(1024);
        if (!think.isBlank()) {
            options.thinkOption(new ThinkOption.ThinkBoolean(Boolean.parseBoolean(think)));
        }
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options.build())
                .build();

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
                .defaultOptions(AnthropicChatOptions.builder().model(model).maxTokens(2048).build())
                .build();
        return ChatClient.builder(chatModel)
                .defaultSystem(AssistantConfig.ASSISTANT_SYSTEM_PROMPT)
                .defaultAdvisors(tokenAudit, guardrails, MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
