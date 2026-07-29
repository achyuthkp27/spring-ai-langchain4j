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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class WidgetHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(WidgetHistoryStore.class);

    public record WidgetRow(int turnSeq, String widgetType, String payload) {
    }

    private final JdbcTemplate jdbc;

    private static final int MAX_QUEUED_WRITES = 10_000;
    private final AtomicLong droppedWrites = new AtomicLong();
    private final ExecutorService persistExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED_WRITES),
            r -> {
                Thread t = new Thread(r, "widget-history-persist");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> {
                long total = droppedWrites.incrementAndGet();
                if (total == 1 || total % 1000 == 0) {
                    log.warn("widget-history.persist.queue_full dropping write (totalDropped={})", total);
                }
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

            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS assistant_turn_counter (
                        conversation_id VARCHAR(256) PRIMARY KEY,
                        next_turn_seq INT NOT NULL DEFAULT 0
                    )
                    """);
        } catch (Exception e) {
            log.warn("widget-history.schema.create skipped: {}", e.getMessage());
        }
    }

    public int nextTurnSeq(String conversationId) {
        return jdbc.queryForObject("""
                INSERT INTO assistant_turn_counter (conversation_id, next_turn_seq)
                VALUES (?, 1)
                ON CONFLICT (conversation_id)
                DO UPDATE SET next_turn_seq = assistant_turn_counter.next_turn_seq + 1
                RETURNING next_turn_seq
                """, Integer.class, conversationId);
    }

    public int currentTurnSeq(String conversationId) {
        List<Integer> rows = jdbc.query(
                "SELECT next_turn_seq FROM assistant_turn_counter WHERE conversation_id = ?",
                (rs, i) -> rs.getInt("next_turn_seq"), conversationId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    @PreDestroy
    public void shutdown() {
        persistExecutor.shutdown();
        try {
            if (!persistExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                persistExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            persistExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

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

    public void deleteConversation(String conversationId) {
        jdbc.update("DELETE FROM assistant_widget_event WHERE conversation_id = ?", conversationId);
        jdbc.update("DELETE FROM assistant_turn_counter WHERE conversation_id = ?", conversationId);
    }
}
