package com.aegis.ai.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

@Component
public class PolicyTools {

    private final VectorStore vectorStore;

    public PolicyTools(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(description = "Search a bank tenant's policy documents and return the most "
            + "relevant passages. Read-only. Requires the tenant id.")
    public String searchPolicies(
            @ToolParam(description = "tenant id, e.g. achu-bank or globex-bank") String tenantId,
            @ToolParam(description = "the policy question to search for") String query) {

        var filter = new FilterExpressionBuilder()
                .eq("tenantId", tenantId).build();
        var results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(4)
                .similarityThreshold(0.1)
                .filterExpression(filter)
                .build());

        if (results == null || results.isEmpty()) {
            return "No matching policy passages for tenant " + tenantId + ".";
        }
        return results.stream()
                .map(d -> "- (" + d.getMetadata().getOrDefault("source", "?") + ") " + d.getText())
                .collect(Collectors.joining("\n"));
    }
}
