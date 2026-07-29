package com.aegis.merged.assistant;

import com.aegis.merged.admin.AuditTrail;
import com.aegis.merged.config.ApiExceptionHandler;
import com.aegis.merged.guardrails.BudgetGuard;
import com.aegis.merged.guardrails.InjectionScreen;
import com.aegis.merged.guardrails.LlmGuard;
import com.aegis.merged.guardrails.PiiRedactor;
import com.aegis.merged.guardrails.RateLimiter;
import com.aegis.merged.rag.SemanticCache;
import com.aegis.merged.tools.BankingTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantControllerValidationTest {

    @SuppressWarnings("unchecked")
    private MockMvc mockMvc() {
        AssistantController controller = new AssistantController(
                mock(ChatClient.class),
                mock(BankingTools.class),
                mock(PolicySearchTool.class),
                mock(SemanticCache.class),
                mock(RateLimiter.class),
                mock(BudgetGuard.class),
                mock(InjectionScreen.class),
                mock(PiiRedactor.class),
                mock(ScopeGate.class),
                mock(LlmGuard.class),
                mock(AuditTrail.class),
                mock(ChatMemory.class),
                mock(ModelRouter.class),
                mock(ObjectProvider.class),
                mock(WidgetHistoryStore.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("An over-length message is rejected with 400, not 500 — the validation error "
            + "must resolve through HttpMessageNotReadableException, not the catch-all handler")
    void overLengthMessageReturns400() throws Exception {
        String hugeMessage = "a".repeat(5000);
        String body = "{\"message\":\"" + hugeMessage + "\"}";

        mockMvc().perform(post("/api/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message must be 4000 characters or fewer."));
    }

    @Test
    @DisplayName("A blank message is rejected with 400")
    void blankMessageReturns400() throws Exception {
        mockMvc().perform(post("/api/assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
