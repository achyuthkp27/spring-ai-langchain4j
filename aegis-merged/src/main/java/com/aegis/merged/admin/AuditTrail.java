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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Audit trail powering the admin dashboard. Every assistant request is recorded with its
 * OUTCOME (llm / cache / the specific guardrail that blocked it / error), latency and sizing,
 * plus every tool invocation.
 *
 * The dashboard reads (recent/countsBySource/timeseries/latency) stay backed by the in-memory
 * bounded ring buffer below — cheap, interactive, no reason to hit Postgres for those. A
 * SEPARATE, additive write persists every event to {@code assistant_audit_event} so the full
 * audit history survives a restart (compliance/forensics), fired asynchronously so a slow or
 * unavailable DB never adds latency to the streaming response path — failures are logged and
 * swallowed, the in-memory trail remains authoritative for the live dashboard regardless.
 *
 * Privacy: the question preview stored here must ALREADY be PII-redacted by the
 * caller — the admin UI (and the persisted row) render it verbatim.
 */
@Component
public class AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditTrail.class);

    public static final int MAX_EVENTS = 2000;
    private static final int PREVIEW_CHARS = 140;

    /** One assistant request, as the admin sees it. */
    public record Event(Instant at, String tenant, String user, String conversationId,
                        String source, long elapsedMs, int answerChars, String question) {
    }

    private final ConcurrentLinkedDeque<Event> events = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final Map<String, LongAdder> bySource = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> toolCalls = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    private final JdbcTemplate jdbc;
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audit-persist");
        t.setDaemon(true);
        return t;
    });

    // Hash-chain tamper-evidence (Phase 2 of the production roadmap): every persisted row
    // includes a SHA-256 hash of its own fields chained to the PREVIOUS row's hash, so any
    // edit or deletion of a historical row — including by someone with raw DB access — breaks
    // the chain at that point, detectable via verifyChain(). This is NOT a substitute for
    // real WORM/immutable storage (Phase 2's real bar), but it turns "was this audit log
    // tampered with" from unanswerable into a cheap, mechanical check, entirely in code.
    // Only ever mutated on persistExecutor's single thread, so no extra locking is needed.
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
            // Idempotent for a table that pre-dates this column (ALTER ... IF NOT EXISTS,
            // supported since Postgres 9.6 for ADD COLUMN).
            jdbc.execute("ALTER TABLE assistant_audit_event ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64) NOT NULL DEFAULT 'GENESIS'");
            jdbc.execute("ALTER TABLE assistant_audit_event ADD COLUMN IF NOT EXISTS row_hash VARCHAR(64) NOT NULL DEFAULT ''");

            try {
                String seeded = jdbc.queryForObject(
                        "SELECT row_hash FROM assistant_audit_event WHERE row_hash <> '' ORDER BY id DESC LIMIT 1",
                        String.class);
                if (seeded != null) lastHash.set(seeded);
            } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
                // No rows yet — chain starts at GENESIS, already the default.
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

    /** Recomputes the hash chain from scratch and reports the first row (if any) where the
        stored hash doesn't match what its own fields + the previous row's hash produce —
        proof the persisted audit log hasn't been edited since it was written.
        {@code legacyRowsSkipped} counts rows with no hash at all — written before this
        feature existed — which are unverifiable by construction, not evidence of tampering;
        the chain is only checked from the first row that actually has one. */
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

        String expectedPrev = null; // set from the first hashed row, not assumed to be GENESIS
        long legacyRowsSkipped = 0;
        for (Object[] r : rows) {
            String recordedHash = (String) r[9];
            if (recordedHash == null || recordedHash.isBlank()) {
                // Row predates the hash-chain feature — unverifiable by construction, not tampering.
                legacyRowsSkipped++;
                continue;
            }
            String recordedPrev = (String) r[8];
            if (expectedPrev == null) {
                // First hashed row: accept whatever prev_hash it recorded (GENESIS, or the last
                // legacy row's absence of a hash) as the start of the verifiable chain.
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

    /** Called from inside the tools so the dashboard shows which capabilities are used. */
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

    /** {tool -> {tenant -> count}} for the dashboard's capability breakdown. */
    public Map<String, Map<String, Long>> toolUsage() {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        toolCalls.forEach((key, v) -> {
            int i = key.indexOf('|');
            out.computeIfAbsent(key.substring(0, i), k -> new TreeMap<>())
               .put(key.substring(i + 1), v.sum());
        });
        return out;
    }

    /** Latency stats (ms) over the retained window, per source and overall. */
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

    /** Per-minute buckets over the last {@code minutes}, oldest first, for the traffic chart. */
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
