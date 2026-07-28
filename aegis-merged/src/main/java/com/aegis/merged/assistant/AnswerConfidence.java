package com.aegis.merged.assistant;

import com.aegis.merged.guardrails.GuardrailAdvisor;

/**
 * Shared low-confidence phrase check. Used by {@link AssistantController} to decide whether an
 * answer is safe to cache, and by {@link ModelRouter} to decide whether the NEXT turn in a
 * conversation should escalate to the bigger model — the same phrases that make an answer
 * unfit to cache also make it a signal that the fast model struggled with this topic.
 */
final class AnswerConfidence {

    private AnswerConfidence() {
    }

    static boolean looksLowConfidence(String answer) {
        if (answer == null) return true;
        String a = answer.toLowerCase();
        return a.contains("i don't have")
                || a.contains(GuardrailAdvisor.REFUSAL.toLowerCase())
                || a.contains("couldn't find")
                || a.contains("could not find")
                || a.contains("don't have that in the available documents")
                || a.contains("i'm not sure");
    }
}
