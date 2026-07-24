package com.aegis.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Widens the Spring AI chat-memory conversation_id column.
 *
 * Spring AI's default JDBC schema sizes conversation_id as varchar(36) — it assumes a
 * UUID. But we key memory by "tenantId:userId:conversationId", which overflows 36 chars
 * as soon as identifiers are realistic (an email userId, a real tenant name). When the
 * insert failed, the whole model call failed and — under repetition — tripped the circuit
 * breaker. Widening the column removes the constraint. Runs once at startup, idempotent.
 */
@Component
public class ChatMemorySchemaFix {

    private static final Logger log = LoggerFactory.getLogger(ChatMemorySchemaFix.class);

    private final JdbcTemplate jdbc;

    public ChatMemorySchemaFix(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)   // after Spring AI has created the table
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
