package com.aegis.merged.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists the structured widget payloads (cards, accounts, transactions, a dispute case,
 * an approval, policy citations) that {@code AssistantController} pushes as SSE side-channel
 * events during a live turn — without this, that data only ever existed for the duration of
 * the streaming response, so reloading the page reconstructed messages from
 * {@code spring_ai_chat_memory} (text only) and every widget silently vanished.
 *
 * Rows are tagged with {@code turnSeq} — the 1-indexed ordinal of the assistant reply within
 * its conversation, computed once at the start of {@code AssistantController.stream()} from
 * the PRIOR message count — so {@code GET /history} can re-attach each turn's widget events
 * to the matching assistant message without needing any change to Spring AI's own chat-memory
 * schema. Multiple emissions of the same widget type within one turn (e.g. listCards then
 * freezeCard both firing in the same turn) are stored as SEPARATE rows, in order, so history
 * replay can reuse the exact same merge-by-id logic the live SSE stream already uses on the
 * frontend — deduping here would risk dropping cards/accounts/transactions the live view
 * never dropped.
 */
@Component
public class WidgetHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(WidgetHistoryStore.class);

    public record WidgetRow(int turnSeq, String widgetType, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "widget-history-persist");
        t.setDaemon(true);
        return t;
    });

    public WidgetHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createSchema() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS assistant_widget_event (
                        id BIGSERIAL PRIMARY KEY,
                        conversation_id VARCHAR(256) NOT NULL,
                        turn_seq INT NOT NULL,
                        widget_type VARCHAR(32) NOT NULL,
                        payload TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            jdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_widget_event_conversation
                        ON assistant_widget_event (conversation_id)
                    """);
        } catch (Exception e) {
            log.warn("widget-history.schema.create skipped: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        persistExecutor.shutdown();
    }

    /** Fire-and-forget: never adds latency to the streaming response path. */
    public void save(String conversationId, int turnSeq, String widgetType, String payloadJson) {
        persistExecutor.submit(() -> {
            try {
                jdbc.update("""
                        INSERT INTO assistant_widget_event (conversation_id, turn_seq, widget_type, payload)
                        VALUES (?, ?, ?, ?)
                        """, conversationId, turnSeq, widgetType, payloadJson);
            } catch (Exception e) {
                log.warn("widget-history.persist.failed: {}", e.getMessage());
            }
        });
    }

    /** Every widget row for a conversation, in insertion order — callers group by turnSeq. */
    public List<WidgetRow> loadForConversation(String conversationId) {
        List<WidgetRow> out = new ArrayList<>();
        jdbc.query("""
                SELECT turn_seq, widget_type, payload FROM assistant_widget_event
                WHERE conversation_id = ? ORDER BY id""",
                rs -> {
                    out.add(new WidgetRow(rs.getInt("turn_seq"), rs.getString("widget_type"), rs.getString("payload")));
                }, conversationId);
        return out;
    }
}
