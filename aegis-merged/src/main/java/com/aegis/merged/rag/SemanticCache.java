package com.aegis.merged.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private static final int MAX_PER_TENANT = 500;
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MIN_WORDS = 4;

    private static final Set<String> CONTEXT_DEPENDENT = Set.of(
            "yes", "no", "ok", "okay", "sure", "yeah", "yep", "nope", "hi", "hello",
            "hey", "thanks", "thank you", "please", "go ahead", "do it", "the first one",
            "that one", "and", "why", "how", "what", "continue", "more", "next");
    private static final Pattern QUESTION_WORDS = Pattern.compile(
            "\\b(what|how|when|where|which|who|why|can|is|are|does|do|deadline|limit|policy|"
            + "tier|dispute|kyc|edd|credit|charge|refund)\\b", Pattern.CASE_INSENSITIVE);

    private record Entry(float[] embedding, String question, String answer, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final Map<String, CopyOnWriteArrayList<Entry>> byTenant = new ConcurrentHashMap<>();
    private final EmbeddingModel embeddingModel;
    private final double similarityThreshold;

    public SemanticCache(EmbeddingModel embeddingModel) {
        this(embeddingModel, 0.62);
    }

    public SemanticCache(EmbeddingModel embeddingModel, double similarityThreshold) {
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
    }

    public record Hit(String answer, double similarity, String matchedQuestion) {
    }

    public boolean isCacheable(String question) {
        if (question == null) return false;
        String norm = question.trim().toLowerCase();
        if (CONTEXT_DEPENDENT.contains(norm.replaceAll("[?.!]", ""))) return false;
        if (norm.split("\\s+").length < MIN_WORDS) return false;   
        return QUESTION_WORDS.matcher(norm).find();                 
    }

    public Optional<Hit> lookup(String tenantId, String question) {
        
        if (!isCacheable(question)) {
            return Optional.empty();
        }
        var entries = byTenant.get(tenantId);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        entries.removeIf(Entry::expired);   
        float[] q = embeddingModel.embed(question);
        Entry best = null;
        double bestSim = -1;
        for (Entry e : entries) {
            double sim = cosine(q, e.embedding());
            if (sim > bestSim) {
                bestSim = sim;
                best = e;
            }
        }
        if (best != null && bestSim >= similarityThreshold) {

            log.info("cache.hit tenant={} sim={} matchedLength={}", tenantId,
                    String.format("%.3f", bestSim), best.question().length());
            return Optional.of(new Hit(best.answer(), bestSim, best.question()));
        }
        log.info("cache.miss tenant={} bestSim={}", tenantId, String.format("%.3f", bestSim));
        return Optional.empty();
    }

    public void put(String tenantId, String question, String answer) {
        if (!isCacheable(question)) {
            log.info("cache.skip.not_standalone tenant={} qLength={}", tenantId, question.length());
            return;
        }
        var entries = byTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        if (entries.size() >= MAX_PER_TENANT) {
            entries.remove(0); 
        }
        entries.add(new Entry(embeddingModel.embed(question), question, answer, Instant.now().plus(TTL)));
    }

    public void clear() {
        byTenant.clear();
        log.info("cache.cleared all tenants");
    }

    public void clear(String tenantId) {
        byTenant.remove(tenantId);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
