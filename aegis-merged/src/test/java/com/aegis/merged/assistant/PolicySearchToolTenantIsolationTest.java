package com.aegis.merged.assistant;

import com.aegis.merged.admin.AuditTrail;
import com.aegis.merged.guardrails.InjectionScreen;
import com.aegis.merged.security.Principal;
import com.aegis.merged.tools.BankingTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicySearchToolTenantIsolationTest {

    private ToolContext ctxFor(String tenantId, String userId) {
        var principal = new Principal(userId, tenantId, Set.of("account:read"));
        return new ToolContext(Map.of(BankingTools.PRINCIPAL_KEY, principal));
    }

    @Test
    @DisplayName("searchPolicies always scopes the vector store filter to the caller's own tenant, "
            + "regardless of what the query text mentions")
    void filterIsAlwaysScopedToTheCallersOwnTenant() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(java.util.List.of());
        var tool = new PolicySearchTool(vectorStore, mock(AuditTrail.class), mock(InjectionScreen.class));

        tool.searchPolicies("what is globex-bank's dispute deadline?", ctxFor("achu-bank", "u1"));

        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        var expected = new FilterExpressionBuilder().eq("tenantId", "achu-bank").build();
        assertThat(captor.getValue().getFilterExpression()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Two different callers get filters scoped to their own, different tenants")
    void differentCallersGetDifferentTenantFilters() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(java.util.List.of());
        var tool = new PolicySearchTool(vectorStore, mock(AuditTrail.class), mock(InjectionScreen.class));

        tool.searchPolicies("dispute deadline", ctxFor("achu-bank", "u1"));
        tool.searchPolicies("dispute deadline", ctxFor("globex-bank", "u2"));

        var captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, org.mockito.Mockito.times(2)).similaritySearch(captor.capture());

        var achuFilter = new FilterExpressionBuilder().eq("tenantId", "achu-bank").build();
        var globexFilter = new FilterExpressionBuilder().eq("tenantId", "globex-bank").build();
        assertThat(captor.getAllValues().get(0).getFilterExpression()).isEqualTo(achuFilter);
        assertThat(captor.getAllValues().get(1).getFilterExpression()).isEqualTo(globexFilter);
    }
}
