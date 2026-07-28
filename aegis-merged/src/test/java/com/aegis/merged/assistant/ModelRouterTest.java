package com.aegis.merged.assistant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouterTest {

    @Test
    @DisplayName("A short, plain question stays on the SIMPLE tier")
    void simpleByDefault() {
        var router = new ModelRouter();
        assertThat(router.decide("What is my balance?", "k1")).isEqualTo(ModelRouter.Tier.SIMPLE);
    }

    @Test
    @DisplayName("Comparison/step-by-step language escalates to COMPLEX")
    void keywordEscalates() {
        var router = new ModelRouter();
        assertThat(router.decide("Compare a checking and savings account for me", "k2"))
                .isEqualTo(ModelRouter.Tier.COMPLEX);
    }

    @Test
    @DisplayName("A very long message escalates to COMPLEX on word count alone")
    void longMessageEscalates() {
        var router = new ModelRouter();
        String long41Words = "word ".repeat(41).trim();
        assertThat(router.decide(long41Words, "k3")).isEqualTo(ModelRouter.Tier.COMPLEX);
    }

    @Test
    @DisplayName("Two or more banking entity ids in one message escalates to COMPLEX")
    void multipleEntityIdsEscalate() {
        var router = new ModelRouter();
        assertThat(router.decide("Compare ACC-1001 and ACC-1002", "k4")).isEqualTo(ModelRouter.Tier.COMPLEX);
    }

    @Test
    @DisplayName("A low-confidence answer escalates only the NEXT turn in that conversation")
    void lowConfidenceEscalatesNextTurn() {
        var router = new ModelRouter();
        String key = "k5";
        assertThat(router.decide("What is my balance?", key)).isEqualTo(ModelRouter.Tier.SIMPLE);

        router.markLowConfidence(key, "I couldn't find that in our policies.", false);

        assertThat(router.decide("What is my balance?", key)).isEqualTo(ModelRouter.Tier.COMPLEX);
    }

    @Test
    @DisplayName("A confident answer with no tool failure does NOT escalate the next turn")
    void confidentAnswerDoesNotEscalate() {
        var router = new ModelRouter();
        String key = "k6";
        router.markLowConfidence(key, "Your balance is $2,500.", false);
        assertThat(router.decide("What is my balance?", key)).isEqualTo(ModelRouter.Tier.SIMPLE);
    }

    @Test
    @DisplayName("A tool failure escalates the next turn even when the answer reads confidently")
    void toolFailureEscalatesRegardlessOfPhrasing() {
        var router = new ModelRouter();
        String key = "k7";
        router.markLowConfidence(key, "Here's what I can tell you about that.", true);
        assertThat(router.decide("What is my balance?", key)).isEqualTo(ModelRouter.Tier.COMPLEX);
    }
}
