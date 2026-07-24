package com.aegis.lc4j.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Postgres-backed ChatMemoryStore. Messages are serialized with LangChain4j's
 * canonical JSON format. memory_id is the composite `tenantId:userId:conversationId`
 * key, so isolation is structural: a user can only ever address their own rows
 * because the prefix comes from the verified JWT, never from the client.
 */
@Component
public class PgChatMemoryStore implements ChatMemoryStore {

    private final JdbcTemplate jdbc;

    public PgChatMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var rows = jdbc.queryForList(
                "SELECT messages FROM lc4j_chat_memory WHERE memory_id = ?", String.class,
                memoryId.toString());
        if (rows.isEmpty()) {
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(rows.get(0));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        jdbc.update("""
                INSERT INTO lc4j_chat_memory (memory_id, messages, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (memory_id) DO UPDATE SET messages = EXCLUDED.messages, updated_at = now()
                """, memoryId.toString(), json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbc.update("DELETE FROM lc4j_chat_memory WHERE memory_id = ?", memoryId.toString());
    }
}
