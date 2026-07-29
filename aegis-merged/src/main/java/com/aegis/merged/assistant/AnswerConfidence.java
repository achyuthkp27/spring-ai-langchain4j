package com.aegis.merged.assistant;

import com.aegis.merged.guardrails.GuardrailAdvisor;

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
