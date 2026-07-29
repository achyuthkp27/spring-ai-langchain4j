package com.aegis.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatMemorySchemaFix {

    private static final Logger log = LoggerFactory.getLogger(ChatMemorySchemaFix.class);

    private final JdbcTemplate jdbc;

    public ChatMemorySchemaFix(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)   
    public void widenConversationId() {
        try {
            jdbc.execute("ALTER TABLE SPRING_AI_CHAT_MEMORY "
                    + "ALTER COLUMN conversation_id TYPE varchar(256)");
            log.info("chat-memory conversation_id widened to varchar(256)");
        } catch (Exception e) {
            log.warn("chat-memory schema widen skipped: {}", e.getMessage());
        }
    }
}
