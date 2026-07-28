package com.aegis.merged.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Two-tier model cascade. A request is classified SIMPLE (fast local model) or COMPLEX
 * (escalation model) BEFORE streaming starts, using cheap regex/heuristic signals — never an
 * extra LLM round-trip, which would cost every request the latency it's trying to save for
 * the rare hard ones.
 *
 * True confidence-gated cascading (run the small model, inspect ITS answer, escalate if weak)
 * would require buffering the whole answer before sending anything to the client, killing
 * time-to-first-token. Instead this router escalates the FOLLOWING turn in a conversation once
 * a low-confidence answer is observed — a session-scoped self-correction instead of a
 * per-token one, kept in step with {@link AnswerConfidence}.
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    public enum Tier { SIMPLE, COMPLEX }

    private static final int WORD_THRESHOLD = 40;

    private static final Pattern COMPLEXITY_KEYWORDS = Pattern.compile(
            "\\b(compare|comparison|difference between|explain in detail|step by step|"
            + "step-by-step|in depth|pros and cons|walk me through)\\b",
            Pattern.CASE_INSENSITIVE);

    // Same entity-id shape ScopeGate.DOMAIN_FASTPATH uses to recognize banking ids.
    private static final Pattern ENTITY_ID = Pattern.compile(
            "\\b(acc|txn|case|apr|crd)-\\d+\\b", Pattern.CASE_INSENSITIVE);

    private static final Duration ESCALATION_TTL = Duration.ofMinutes(10);

    private record Escalation(Instant expiresAt) {
        boolean expired() { return Instant.now().isAfter(expiresAt); }
    }

    private final ConcurrentHashMap<String, Escalation> escalated = new ConcurrentHashMap<>();

    /** Classify a request BEFORE the stream starts. Cheap — no LLM call. */
    public Tier decide(String message, String memoryKey) {
        Escalation e = escalated.get(memoryKey);
        if (e != null) {
            if (!e.expired()) {
                log.info("router.tier=COMPLEX reason=escalated-conversation key={}", memoryKey);
                return Tier.COMPLEX;
            }
            escalated.remove(memoryKey);
        }

        if (message == null) return Tier.SIMPLE;
        Tier tier = isComplex(message) ? Tier.COMPLEX : Tier.SIMPLE;
        log.info("router.tier={} key={}", tier, memoryKey);
        return tier;
    }

    private static boolean isComplex(String message) {
        if (message.split("\\s+").length > WORD_THRESHOLD) return true;
        if (COMPLEXITY_KEYWORDS.matcher(message).find()) return true;
        if (countMatches(ENTITY_ID, message) >= 2) return true;
        return countChar(message, '?') >= 2;
    }

    private static int countMatches(Pattern p, String s) {
        var m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /**
     * Call after a turn completes: a low-confidence answer OR an actual tool failure (a
     * lookup that came back empty/not-found, per {@link com.aegis.merged.tools.BankingTools#TOOL_FAILED_KEY})
     * escalates the NEXT turn in this conversation. The tool signal catches failures the
     * model might phrase confidently, which {@link AnswerConfidence}'s phrase match alone
     * would miss.
     */
    public void markLowConfidence(String memoryKey, String answer, boolean toolFailed) {
        if (toolFailed || AnswerConfidence.looksLowConfidence(answer)) {
            escalated.put(memoryKey, new Escalation(Instant.now().plus(ESCALATION_TTL)));
            log.info("router.escalating-next-turn key={} toolFailed={}", memoryKey, toolFailed);
        }
    }
}
