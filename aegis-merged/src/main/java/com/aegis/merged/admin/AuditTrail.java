package com.aegis.merged.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

@Component
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    public static final int MAX_EVENTS = 2000;
    private static final int PREVIEW_CHARS = 140;

    public record Event(Instant at, String tenant, String user, String conversationId,
                        String source, long elapsedMs, int answerChars, String question) {
    }

    private final ConcurrentLinkedDeque<Event> events = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final Map<String, LongAdder> bySource = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> toolCalls = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    private final JdbcTemplate jdbc;

    private static final int MAX_QUEUED_WRITES = 10_000;
    private final AtomicLong droppedWrites = new AtomicLong();
    private final ExecutorService persistExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED_WRITES),
            r -> {
                Thread t = new Thread(r, "audit-persist");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> {
                long total = droppedWrites.incrementAndGet();
                if (total == 1 || total % 1000 == 0) {
                    log.warn("audit.persist.queue_full dropping write (totalDropped={})", total);
                }
            });

    private final AtomicReference<String> lastHash = new AtomicReference<>("GENESIS");

    public AuditTrail(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createSchema() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS assistant_audit_event (
                        id BIGSERIAL PRIMARY KEY,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        tenant VARCHAR(256),
                        user_id VARCHAR(256),
                        conversation_id VARCHAR(256),
                        source VARCHAR(64),
                        elapsed_ms BIGINT,
                        answer_chars INT,
                        question TEXT,
                        prev_hash VARCHAR(64) NOT NULL DEFAULT 'GENESIS',
                        row_hash VARCHAR(64) NOT NULL DEFAULT ''
                    )
                    """);

            jdbc.execute("ALTER TABLE assistant_audit_event ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64) NOT NULL DEFAULT 'GENESIS'");
            jdbc.execute("ALTER TABLE assistant_audit_event ADD COLUMN IF NOT EXISTS row_hash VARCHAR(64) NOT NULL DEFAULT ''");

            try {
                String seeded = jdbc.queryForObject(
                        "SELECT row_hash FROM assistant_audit_event WHERE row_hash <> '' ORDER BY id DESC LIMIT 1",
                        String.class);
                if (seeded != null) lastHash.set(seeded);
            } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
                
            }
        } catch (Exception e) {
            log.warn("audit.schema.create skipped: {}", e.getMessage());
        }
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    public void record(String tenant, String user, String conversationId,
                       String source, long elapsedMs, int answerChars, String redactedQuestion) {
        String q = redactedQuestion == null ? "" : redactedQuestion;
        if (q.length() > PREVIEW_CHARS) q = q.substring(0, PREVIEW_CHARS) + "…";
        events.addFirst(new Event(Instant.now(), tenant, user, conversationId,
                source, elapsedMs, answerChars, q));
        bySource.computeIfAbsent(source, k -> new LongAdder()).increment();
        if (size.incrementAndGet() > MAX_EVENTS) {
            events.pollLast();
            size.decrementAndGet();
        }
        persistAsync(tenant, user, conversationId, source, elapsedMs, answerChars, q);
    }

    private void persistAsync(String tenant, String user, String conversationId,
                              String source, long elapsedMs, int answerChars, String question) {
        persistExecutor.submit(() -> {
            try {
                String prev = lastHash.get();
                String row = sha256Hex(prev + "|" + tenant + "|" + user + "|" + conversationId + "|" + source
                        + "|" + elapsedMs + "|" + answerChars + "|" + question);
                jdbc.update("""
                        INSERT INTO assistant_audit_event
                            (tenant, user_id, conversation_id, source, elapsed_ms, answer_chars, question,
                             prev_hash, row_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, tenant, user, conversationId, source, elapsedMs, answerChars, question, prev, row);
                lastHash.set(row);
            } catch (Exception e) {
                log.warn("audit.persist.failed: {}", e.getMessage());
            }
        });
    }

    public record ChainVerification(boolean valid, long rowsChecked, long legacyRowsSkipped, Long brokenAtId) {
    }

    public ChainVerification verifyChain() {
        var rows = jdbc.query("""
                SELECT id, tenant, user_id, conversation_id, source, elapsed_ms, answer_chars, question,
                       prev_hash, row_hash
                FROM assistant_audit_event ORDER BY id ASC""",
                (rs, i) -> new Object[]{
                        rs.getLong("id"), rs.getString("tenant"), rs.getString("user_id"),
                        rs.getString("conversation_id"), rs.getString("source"), rs.getLong("elapsed_ms"),
                        rs.getInt("answer_chars"), rs.getString("question"),
                        rs.getString("prev_hash"), rs.getString("row_hash")});

        String expectedPrev = null; 
        long legacyRowsSkipped = 0;
        for (Object[] r : rows) {
            String recordedHash = (String) r[9];
            if (recordedHash == null || recordedHash.isBlank()) {
                
                legacyRowsSkipped++;
                continue;
            }
            String recordedPrev = (String) r[8];
            if (expectedPrev == null) {

                expectedPrev = recordedPrev;
            } else if (!expectedPrev.equals(recordedPrev)) {
                return new ChainVerification(false, rows.size(), legacyRowsSkipped, (Long) r[0]);
            }
            String recomputed = sha256Hex(recordedPrev + "|" + r[1] + "|" + r[2] + "|" + r[3] + "|" + r[4]
                    + "|" + r[5] + "|" + r[6] + "|" + r[7]);
            if (!recomputed.equals(recordedHash)) {
                return new ChainVerification(false, rows.size(), legacyRowsSkipped, (Long) r[0]);
            }
            expectedPrev = recordedHash;
        }
        return new ChainVerification(true, rows.size(), legacyRowsSkipped, null);
    }

    public void toolCalled(String tool, String tenant) {
        toolCalls.computeIfAbsent(tool + "|" + tenant, k -> new LongAdder()).increment();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public List<Event> recent(int limit) {
        List<Event> out = new ArrayList<>(Math.min(limit, size.get()));
        for (Event e : events) {
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public Map<String, Long> countsBySource() {
        Map<String, Long> out = new TreeMap<>();
        bySource.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public Map<String, Map<String, Long>> toolUsage() {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        toolCalls.forEach((key, v) -> {
            int i = key.indexOf('|');
            out.computeIfAbsent(key.substring(0, i), k -> new TreeMap<>())
               .put(key.substring(i + 1), v.sum());
        });
        return out;
    }

    public Map<String, Map<String, Long>> latency() {
        Map<String, List<Long>> samples = new TreeMap<>();
        for (Event e : events) {
            samples.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.elapsedMs());
            samples.computeIfAbsent("all", k -> new ArrayList<>()).add(e.elapsedMs());
        }
        Map<String, Map<String, Long>> out = new TreeMap<>();
        samples.forEach((src, list) -> {
            list.sort(Comparator.naturalOrder());
            long avg = Math.round(list.stream().mapToLong(Long::longValue).average().orElse(0));
            out.put(src, Map.of(
                    "count", (long) list.size(),
                    "avg", avg,
                    "p50", percentile(list, 50),
                    "p95", percentile(list, 95),
                    "max", list.get(list.size() - 1)));
        });
        return out;
    }

    public List<Map<String, Object>> timeseries(int minutes) {
        Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        Map<Instant, Map<String, Long>> buckets = new TreeMap<>();
        for (Event e : events) {
            if (e.at().isBefore(cutoff)) continue;
            Instant min = e.at().truncatedTo(ChronoUnit.MINUTES);
            buckets.computeIfAbsent(min, k -> new java.util.HashMap<>())
                   .merge(bucketKey(e.source()), 1L, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Instant t = cutoff;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        while (!t.isAfter(now)) {
            Map<String, Long> b = buckets.getOrDefault(t, Map.of());
            out.add(Map.of("minute", t.toString(),
                    "llm", b.getOrDefault("llm", 0L),
                    "cache", b.getOrDefault("cache", 0L),
                    "blocked", b.getOrDefault("blocked", 0L),
                    "error", b.getOrDefault("error", 0L)));
            t = t.plus(1, ChronoUnit.MINUTES);
        }
        return out;
    }

    private static String bucketKey(String source) {
        if (source.startsWith("blocked")) return "blocked";
        if (source.equals("llm") || source.equals("cache")) return source;
        return "error";
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
