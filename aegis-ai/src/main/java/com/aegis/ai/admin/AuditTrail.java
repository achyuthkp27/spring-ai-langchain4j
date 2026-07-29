package com.aegis.ai.admin;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

@Component
public class AuditTrail {

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
