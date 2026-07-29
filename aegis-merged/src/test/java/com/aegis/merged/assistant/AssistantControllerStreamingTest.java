package com.aegis.merged.assistant;

import com.aegis.merged.admin.AuditTrail;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantControllerStreamingTest {

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static AssistantController newController() {
        return newController(mock(WidgetHistoryStore.class));
    }

    @SuppressWarnings("unchecked")
    private static AssistantController newController(WidgetHistoryStore widgetHistoryStore) {
        PiiRedactor piiRedactor = mock(PiiRedactor.class);
        when(piiRedactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));

        return new AssistantController(
                mock(ChatClient.class),
                mock(BankingTools.class),
                mock(PolicySearchTool.class),
                mock(SemanticCache.class),
                mock(RateLimiter.class),
                mock(BudgetGuard.class),
                mock(InjectionScreen.class),
                piiRedactor,
                mock(ScopeGate.class),
                mock(LlmGuard.class),
                mock(AuditTrail.class),
                mock(ChatMemory.class),
                mock(ModelRouter.class),
                mock(ObjectProvider.class),
                widgetHistoryStore);
    }

    @Test
    @DisplayName("Token events are emitted at each sentence boundary as chunks arrive, "
            + "not buffered until the whole response is generated")
    void tokensStreamIncrementally() {
        AssistantController controller = newController();
        var state = new AssistantController.StreamState();

        Flux<ChatResponse> responses = Flux.just(
                chatResponse("First sentence. "),
                chatResponse("Second sentence. "),
                chatResponse("trailing partial"));

        Flux<ServerSentEvent<String>> events = controller.streamTokens(responses, state);

        StepVerifier.create(events)
                .expectNextMatches(e -> e.data() != null && e.data().contains("First sentence."))
                .expectNextMatches(e -> e.data() != null && e.data().contains("Second sentence."))
                .expectNextMatches(e -> e.data() != null && e.data().contains("trailing partial"))
                .verifyComplete();
    }

    @Test
    @DisplayName("A response with no sentence boundary at all is still flushed as one final token event")
    void singleUnterminatedChunkIsFlushedOnCompletion() {
        AssistantController controller = newController();
        var state = new AssistantController.StreamState();

        Flux<ChatResponse> responses = Flux.just(chatResponse("no terminal punctuation here"));

        StepVerifier.create(controller.streamTokens(responses, state))
                .expectNextMatches(e -> e.data() != null && e.data().contains("no terminal punctuation here"))
                .verifyComplete();
    }

    @Test
    @DisplayName("The first token event arrives before the source flux completes, "
            + "not only after every chunk has been generated")
    void firstTokenArrivesBeforeSourceCompletes() {
        AssistantController controller = newController();
        var state = new AssistantController.StreamState();
        Sinks.Many<ChatResponse> source = Sinks.many().unicast().onBackpressureBuffer();

        StepVerifier.create(controller.streamTokens(source.asFlux(), state))
                .then(() -> source.tryEmitNext(chatResponse("First sentence. ")))
                .assertNext(e -> assertThat(e.data()).contains("First sentence."))
                .then(() -> {
                    source.tryEmitNext(chatResponse("Second sentence."));
                    source.tryEmitComplete();
                })
                .assertNext(e -> assertThat(e.data()).contains("Second sentence."))
                .verifyComplete();
    }

    @Test
    @DisplayName("A truncated (finish_reason=length) answer is not cacheable")
    void truncatedAnswerIsNotCacheable() {
        assertThat(AssistantController.isSafeToCache("A complete answer.", true)).isFalse();
    }

    @Test
    @DisplayName("A complete, non-dynamic, non-mutating answer is cacheable")
    void completeAnswerIsCacheable() {
        assertThat(AssistantController.isSafeToCache("A complete answer.", false)).isTrue();
    }

    @Test
    @DisplayName("Widgets produced before a cancelled turn are persisted under currentTurnSeq+1 "
            + "WITHOUT incrementing the counter, so the next completed turn reclaims that seq "
            + "and history() alignment never drifts")
    void pendingWidgetsArePersistedOnCancelWithoutAdvancingTheCounter() {
        WidgetHistoryStore widgetHistoryStore = mock(WidgetHistoryStore.class);
        when(widgetHistoryStore.currentTurnSeq(anyString())).thenReturn(4);
        AssistantController controller = newController(widgetHistoryStore);

        var pendingWidgets = List.of(new AssistantController.PendingWidget("cards", "{\"cardId\":\"CRD-1\"}"));

        controller.persistCancelledTurnWidgets("achu-bank:u1:c1", pendingWidgets);

        verify(widgetHistoryStore, never()).nextTurnSeq(anyString());
        verify(widgetHistoryStore).save("achu-bank:u1:c1", 5, "cards", "{\"cardId\":\"CRD-1\"}");
    }

    @Test
    @DisplayName("A cancelled turn with no widgets touches neither the counter nor the store")
    void noTurnSeqAllocatedWhenNothingToPersist() {
        WidgetHistoryStore widgetHistoryStore = mock(WidgetHistoryStore.class);
        AssistantController controller = newController(widgetHistoryStore);

        controller.persistCancelledTurnWidgets("achu-bank:u1:c1", List.of());

        verify(widgetHistoryStore, never()).currentTurnSeq(anyString());
        verify(widgetHistoryStore, never()).nextTurnSeq(anyString());
        verify(widgetHistoryStore, times(0)).save(anyString(), anyInt(), anyString(), anyString());
    }
}
