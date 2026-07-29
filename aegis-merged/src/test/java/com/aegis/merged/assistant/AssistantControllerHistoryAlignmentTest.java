package com.aegis.merged.assistant;

import com.aegis.merged.admin.AuditTrail;
import com.aegis.merged.guardrails.BudgetGuard;
import com.aegis.merged.guardrails.InjectionScreen;
import com.aegis.merged.guardrails.LlmGuard;
import com.aegis.merged.guardrails.PiiRedactor;
import com.aegis.merged.guardrails.RateLimiter;
import com.aegis.merged.rag.SemanticCache;
import com.aegis.merged.security.Principal;
import com.aegis.merged.tools.BankingTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantControllerHistoryAlignmentTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private static AssistantController newController(ChatMemory chatMemory, WidgetHistoryStore widgetHistoryStore) {
        return new AssistantController(
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
                chatMemory,
                mock(ModelRouter.class),
                mock(ObjectProvider.class),
                widgetHistoryStore);
    }

    @Test
    @DisplayName("A cancelled turn's widgets (persisted without advancing the counter) correctly "
            + "reattach to the next completed turn's assistant message once it lands, instead of "
            + "permanently misaligning every widget after it")
    void widgetsStayAlignedAcrossACancelledTurn() {
        var principal = new Principal("u1", "achu-bank", Set.of("account:read"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));

        String memoryKey = "achu-bank:u1:c1";
        ChatMemory chatMemory = mock(ChatMemory.class);
        WidgetHistoryStore widgetHistoryStore = mock(WidgetHistoryStore.class);

        when(chatMemory.get(memoryKey)).thenReturn(List.of(
                new UserMessage("what's my balance?"),
                new AssistantMessage("Your balance is $2,500."),
                new UserMessage("freeze my card"),
                new AssistantMessage("Your card is frozen.")));

        when(widgetHistoryStore.currentTurnSeq(memoryKey)).thenReturn(2);
        when(widgetHistoryStore.loadForConversation(memoryKey)).thenReturn(List.of(
                new WidgetHistoryStore.WidgetRow(1, "accounts", "{\"balance\":2500}"),
                new WidgetHistoryStore.WidgetRow(2, "cards", "{\"status\":\"FROZEN\"}")));

        AssistantController controller = newController(chatMemory, widgetHistoryStore);
        List<AssistantController.HistoryMessage> history = controller.history("c1");

        assertThat(history).hasSize(4);
        var firstAssistantMsg = history.get(1);
        var secondAssistantMsg = history.get(3);

        assertThat(firstAssistantMsg.text()).isEqualTo("Your balance is $2,500.");
        assertThat(firstAssistantMsg.widgets()).hasSize(1);
        assertThat(firstAssistantMsg.widgets().get(0).type()).isEqualTo("accounts");

        assertThat(secondAssistantMsg.text()).isEqualTo("Your card is frozen.");
        assertThat(secondAssistantMsg.widgets()).hasSize(1);
        assertThat(secondAssistantMsg.widgets().get(0).type()).isEqualTo("cards");
    }
}
