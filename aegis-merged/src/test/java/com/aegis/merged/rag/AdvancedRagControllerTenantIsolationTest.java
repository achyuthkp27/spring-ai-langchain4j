package com.aegis.merged.rag;

import com.aegis.merged.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.prompt.Prompt;

class AdvancedRagControllerTenantIsolationTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String tenantId, String userId) {
        var principal = new Principal(userId, tenantId, Set.of("account:read"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    @Test
    @DisplayName("/api/rag/ask-advanced scopes the vector store filter to the caller's own tenant, "
            + "never a caller-supplied one")
    void filterIsScopedToTheCallersOwnTenant() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ChatClient ragClient = builder.build();

        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        SemanticCache semanticCache = mock(SemanticCache.class);
        when(semanticCache.lookup(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        var controller = new AdvancedRagController(ragClient, builder, vectorStore, semanticCache);

        authenticateAs("achu-bank", "u1");
        controller.ask(new AdvancedRagController.AskRequest("c1", "what is globex-bank's dispute deadline?"));

        var captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        var expected = new FilterExpressionBuilder().eq("tenantId", "achu-bank").build();
        assertThat(captor.getValue().getFilterExpression()).isEqualTo(expected);
    }
}
