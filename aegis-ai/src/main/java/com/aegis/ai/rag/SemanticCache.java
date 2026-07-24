package com.aegis.ai.rag;

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

/**
 * Tenant-scoped semantic cache. A rephrased-but-equivalent question hits the cache
 * (embedding + cosine compare, ~tens of ms) instead of the full LLM path (seconds).
 *
 * Banking safety rules built in:
 *  - Per-tenant store: one tenant's answers can never be served to another.
 *  - HIGH similarity threshold: only reuse when it's really the same question, so a
 *    "close but different" question (dispute vs KYC deadline) never collides.
 *  - clear(): callers invalidate on policy change so stale answers can't be served.
 *
 * The cache uses a DEDICATED symmetric sentence-similarity model (all-minilm),
 * NOT the RAG retrieval embedding (nomic). These are different jobs: retrieval
 * ranks docs against a query; a cache asks "is this the same question?". Measured
 * on our corpus, all-minilm separates true paraphrases (>=0.74) from different-but-
 * related questions (<=0.47) — a ~0.27 margin, vs nomic's ~0.09 (which caused
 * "what is dispute?" to collide with the deadline answer). See {@link com.aegis.ai.config.CacheConfig}.
 */
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);
    // Calibrated for all-minilm on the banking corpus: paraphrases land >=0.736,
    // different questions <=0.469. 0.62 sits in the middle of that gap with wide
    // margin on both sides (accepts real rephrasings, rejects related-but-different).
    private static final double THRESHOLD = 0.62;
    private static final int MAX_PER_TENANT = 500;
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MIN_WORDS = 4;   // shorter inputs aren't standalone questions

    // Context-dependent inputs whose meaning depends on prior turns — never cacheable.
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

    public SemanticCache(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public record Hit(String answer, double similarity, String matchedQuestion) {
    }

    /**
     * A standalone, cacheable question? Context-dependent inputs ("yes", "why",
     * pronouns) and very short inputs are NOT — their meaning depends on the
     * conversation, so a cached answer would be served wrongly to a later, unrelated
     * use of the same word (the "yes" cache-poisoning bug).
     */
    public boolean isCacheable(String question) {
        if (question == null) return false;
        String norm = question.trim().toLowerCase();
        if (CONTEXT_DEPENDENT.contains(norm.replaceAll("[?.!]", ""))) return false;
        if (norm.split("\\s+").length < MIN_WORDS) return false;   // too short to stand alone
        return QUESTION_WORDS.matcher(norm).find();                 // looks like a real question
    }

    /** Returns a cached answer if a past question for this tenant is similar enough. */
    public Optional<Hit> lookup(String tenantId, String question) {
        // Never even attempt a cache hit for a context-dependent input.
        if (!isCacheable(question)) {
            return Optional.empty();
        }
        var entries = byTenant.get(tenantId);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        entries.removeIf(Entry::expired);   // lazy TTL eviction
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
        if (best != null && bestSim >= THRESHOLD) {
            log.info("cache.hit tenant={} sim={} matched='{}'", tenantId,
                    String.format("%.3f", bestSim), best.question());
            return Optional.of(new Hit(best.answer(), bestSim, best.question()));
        }
        log.info("cache.miss tenant={} bestSim={}", tenantId, String.format("%.3f", bestSim));
        return Optional.empty();
    }

    /** Store a freshly-generated answer for future rephrasings (standalone questions only). */
    public void put(String tenantId, String question, String answer) {
        if (!isCacheable(question)) {
            log.info("cache.skip.not_standalone tenant={} q='{}'", tenantId, question);
            return;
        }
        var entries = byTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>());
        if (entries.size() >= MAX_PER_TENANT) {
            entries.remove(0); // simple FIFO bound
        }
        entries.add(new Entry(embeddingModel.embed(question), question, answer, Instant.now().plus(TTL)));
    }

    /** Invalidate everything (e.g. after a policy re-ingest) or one tenant. */
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
