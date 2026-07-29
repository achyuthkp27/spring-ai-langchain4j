package com.aegis.merged.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagRequestValidationTest {

    @Test
    @DisplayName("RagController.AskRequest rejects a conversationId that would overflow "
            + "spring_ai_chat_memory.conversation_id VARCHAR(256) once the tenant:user: prefix is added")
    void ragAskRequestRejectsAnOversizedConversationId() {
        String oversized = "c".repeat(200);
        assertThatThrownBy(() -> new RagController.AskRequest(oversized, "what is the dispute deadline?"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RagController.AskRequest rejects a blank question")
    void ragAskRequestRejectsABlankQuestion() {
        assertThatThrownBy(() -> new RagController.AskRequest("c1", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AdvancedRagController.AskRequest rejects a conversationId that would overflow "
            + "spring_ai_chat_memory.conversation_id VARCHAR(256) once the tenant:user: prefix is added")
    void advancedRagAskRequestRejectsAnOversizedConversationId() {
        String oversized = "c".repeat(200);
        assertThatThrownBy(() -> new AdvancedRagController.AskRequest(oversized, "what is the dispute deadline?"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AdvancedRagController.AskRequest rejects an oversized question")
    void advancedRagAskRequestRejectsAnOversizedQuestion() {
        String hugeQuestion = "a".repeat(5000);
        assertThatThrownBy(() -> new AdvancedRagController.AskRequest("c1", hugeQuestion))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
