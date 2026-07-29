package com.aegis.merged.evals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/aegis",
        "spring.datasource.username=aegis",
        "spring.datasource.password=aegis"
})
@EnabledIfEnvironmentVariable(named = "RUN_EVAL_TESTS", matches = "true")
class RagEvaluationTest {

    @Autowired
    ChatClient.Builder builder;

    private EvaluationResponse evaluate(RelevancyEvaluator evaluator, String question, String context, String answer) {
        var request = new EvaluationRequest(
                question,
                List.of(new org.springframework.ai.document.Document(context)),
                answer);
        return evaluator.evaluate(request);
    }

    @Test
    @DisplayName("Eval gate catches an answer that is not grounded in the context")
    void evalGateCatchesUngroundedAnswer() {
        var evaluator = new RelevancyEvaluator(builder);
        var bad = evaluate(evaluator,
                "What is the card dispute filing deadline?",
                "Customers must file a dispute for a card transaction within 120 days of the posting date.",
                "The bank's cafeteria serves lunch until 3pm.");
        assertThat(bad.isPass())
                .as("an off-topic answer must fail the relevancy gate")
                .isFalse();
    }
}
