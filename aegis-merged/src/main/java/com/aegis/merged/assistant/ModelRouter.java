package com.aegis.merged.assistant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    public enum Tier { SIMPLE, COMPLEX }

    private static final int WORD_THRESHOLD = 40;

    private static final Pattern COMPLEXITY_KEYWORDS = Pattern.compile(
            "\\b(compare|comparison|difference between|explain in detail|step by step|"
            + "step-by-step|in depth|pros and cons|walk me through)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ENTITY_ID = Pattern.compile(
            "\\b(acc|txn|case|apr|crd)-\\d+\\b", Pattern.CASE_INSENSITIVE);

    private static final Duration ESCALATION_TTL = Duration.ofMinutes(10);
    private static final int MAX_ESCALATIONS = 1_000;

    private final Cache<String, Boolean> escalated = Caffeine.newBuilder()
            .maximumSize(MAX_ESCALATIONS)
            .expireAfterWrite(ESCALATION_TTL)
            .build();

    public Tier decide(String message, String memoryKey) {
        if (escalated.getIfPresent(memoryKey) != null) {
            log.info("router.tier=COMPLEX reason=escalated-conversation key={}", memoryKey);
            return Tier.COMPLEX;
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

    public void markLowConfidence(String memoryKey, String answer, boolean toolFailed) {
        if (toolFailed || AnswerConfidence.looksLowConfidence(answer)) {
            escalated.put(memoryKey, Boolean.TRUE);
            log.info("router.escalating-next-turn key={} toolFailed={}", memoryKey, toolFailed);
        }
    }
}
