package com.aegis.ai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared conversation memory for the assistant. The old general-purpose
 * "copilot" ChatClient bean lived here too; it was removed when the app was
 * consolidated onto the single streaming assistant endpoint (assistantClient
 * in AssistantConfig is now the only conversational client).
 */
@Configuration
public class CopilotConfig {

    @Bean
    ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
