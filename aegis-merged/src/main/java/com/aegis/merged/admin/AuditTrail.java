package com.aegis.merged.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.HashMap;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

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
    private final TransactionTemplate txTemplate;

    private static final int MAX_QUEUED_WRITES = 10_000;
    private static final long ADVISORY_LOCK_KEY = 727310123456789L;
    private static final String DEV_DEFAULT_HMAC_SECRET = "DEV-ONLY-AUDIT-CHAIN-SECRET-CHANGE-ME";
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

    private final SecretKeySpec hmacKey;
    private final boolean usingDevDefaultSecret;

    public AuditTrail(JdbcTemplate jdbc) {
        this(jdbc, transactionManagerFor(jdbc), null);
    }

    private static PlatformTransactionManager transactionManagerFor(JdbcTemplate jdbc) {
        var dataSource = jdbc.getDataSource();
        return dataSource != null ? new DataSourceTransactionManager(dataSource) : new NoOpTransactionManager();
    }

    private static final class NoOpTransactionManager
            extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Autowired
    public AuditTrail(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                      @Value("${aegis.audit.hmac-secret:}") String hmacSecret) {
        this.jdbc = jdbc;
        this.txTemplate = new TransactionTemplate(txManager);
        this.usingDevDefaultSecret = hmacSecret == null || hmacSecret.isBlank();
        String secret = usingDevDefaultSecret ? DEV_DEFAULT_HMAC_SECRET : hmacSecret;
        this.hmacKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfUsingDevDefaultSecret() {
        if (usingDevDefaultSecret) {
            log.error("SECURITY: aegis.audit.hmac-secret is unset — using the built-in DEV-ONLY default. "
                    + "The audit hash chain is forgeable by anyone who reads this source file. "
                    + "Set aegis.audit.hmac-secret to a real secret before any shared/prod use.");
        }
    }

    private String hmacHex(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
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
                txTemplate.executeWithoutResult(status -> {
                    jdbc.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
                    String prev = jdbc.query(
                            "SELECT row_hash FROM assistant_audit_event WHERE row_hash <> '' ORDER BY id DESC LIMIT 1",
                            rs -> rs.next() ? rs.getString(1) : null);
                    if (prev == null || prev.isBlank()) prev = "GENESIS";
                    String row = hmacHex(prev + "|" + tenant + "|" + user + "|" + conversationId + "|" + source
                            + "|" + elapsedMs + "|" + answerChars + "|" + question);
                    jdbc.update("""
                            INSERT INTO assistant_audit_event
                                (tenant, user_id, conversation_id, source, elapsed_ms, answer_chars, question,
                                 prev_hash, row_hash)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, tenant, user, conversationId, source, elapsedMs, answerChars, question, prev, row);
                });
            } catch (Exception e) {
                log.warn("audit.persist.failed: {}", e.getMessage());
            }
        });
    }

    private static final String GENESIS = "GENESIS";

    public record ChainVerification(boolean valid, long rowsChecked, long legacyRowsSkipped,
                                    Long brokenAtId, boolean chainHeadMissing) {
    }

    public ChainVerification verifyChain() {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicLong rowsChecked = new AtomicLong();
        AtomicLong legacyRowsSkipped = new AtomicLong();
        AtomicReference<String> expectedPrev = new AtomicReference<>();
        AtomicReference<Long> brokenAtId = new AtomicReference<>();
        AtomicBoolean chainHeadMissing = new AtomicBoolean(false);

        jdbc.query("""
                SELECT id, tenant, user_id, conversation_id, source, elapsed_ms, answer_chars, question,
                       prev_hash, row_hash
                FROM assistant_audit_event ORDER BY id ASC""",
                rs -> {
                    if (!valid.get()) return;
                    long id = rs.getLong("id");
                    String recordedHash = rs.getString("row_hash");
                    rowsChecked.incrementAndGet();
                    if (recordedHash == null || recordedHash.isBlank()) {
                        legacyRowsSkipped.incrementAndGet();
                        return;
                    }
                    String recordedPrev = rs.getString("prev_hash");
                    if (expectedPrev.get() == null) {
                        if (!GENESIS.equals(recordedPrev)) {
                            valid.set(false);
                            chainHeadMissing.set(true);
                            brokenAtId.set(id);
                            return;
                        }
                        expectedPrev.set(recordedPrev);
                    } else if (!expectedPrev.get().equals(recordedPrev)) {
                        valid.set(false);
                        brokenAtId.set(id);
                        return;
                    }
                    String recomputed = hmacHex(recordedPrev + "|" + rs.getString("tenant") + "|"
                            + rs.getString("user_id") + "|" + rs.getString("conversation_id") + "|"
                            + rs.getString("source") + "|" + rs.getLong("elapsed_ms") + "|"
                            + rs.getInt("answer_chars") + "|" + rs.getString("question"));
                    if (!recomputed.equals(recordedHash)) {
                        valid.set(false);
                        brokenAtId.set(id);
                        return;
                    }
                    expectedPrev.set(recordedHash);
                });

        return new ChainVerification(valid.get(), rowsChecked.get(), legacyRowsSkipped.get(),
                brokenAtId.get(), chainHeadMissing.get());
    }

    public void toolCalled(String tool, String tenant) {
        toolCalls.computeIfAbsent(tool + "|" + tenant, k -> new LongAdder()).increment();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public List<Event> recent(int limit) {
        return recent(null, limit);
    }

    public List<Event> recent(String tenant, int limit) {
        List<Event> out = new ArrayList<>(Math.min(limit, size.get()));
        for (Event e : events) {
            if (tenant != null && !tenant.equals(e.tenant())) continue;
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

    public Map<String, Long> countsBySource(String tenant) {
        if (tenant == null) return countsBySource();
        Map<String, Long> out = new TreeMap<>();
        for (Event e : events) {
            if (!tenant.equals(e.tenant())) continue;
            out.merge(e.source(), 1L, Long::sum);
        }
        return out;
    }

    public Map<String, Map<String, Long>> toolUsage() {
        return toolUsage(null);
    }

    public Map<String, Map<String, Long>> toolUsage(String tenant) {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        toolCalls.forEach((key, v) -> {
            int i = key.indexOf('|');
            String tool = key.substring(0, i);
            String t = key.substring(i + 1);
            if (tenant != null && !tenant.equals(t)) return;
            out.computeIfAbsent(tool, k -> new TreeMap<>()).put(t, v.sum());
        });
        return out;
    }

    public Map<String, Map<String, Long>> latency() {
        return latency(null);
    }

    public Map<String, Map<String, Long>> latency(String tenant) {
        Map<String, List<Long>> samples = new TreeMap<>();
        for (Event e : events) {
            if (tenant != null && !tenant.equals(e.tenant())) continue;
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
        return timeseries(null, minutes);
    }

    public List<Map<String, Object>> timeseries(String tenant, int minutes) {
        Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        Map<Instant, Map<String, Long>> buckets = new TreeMap<>();
        for (Event e : events) {
            if (e.at().isBefore(cutoff)) continue;
            if (tenant != null && !tenant.equals(e.tenant())) continue;
            Instant min = e.at().truncatedTo(ChronoUnit.MINUTES);
            buckets.computeIfAbsent(min, k -> new HashMap<>())
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
