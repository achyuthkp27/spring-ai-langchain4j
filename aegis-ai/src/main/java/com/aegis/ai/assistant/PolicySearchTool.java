package com.aegis.ai.assistant;

import com.aegis.ai.security.AccessDeniedException;
import com.aegis.ai.security.Principal;
import com.aegis.ai.tools.BankingTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class PolicySearchTool {

    private static final Logger log = LoggerFactory.getLogger(PolicySearchTool.class);
    private final VectorStore vectorStore;
    private final com.aegis.ai.admin.AuditTrail audit;

    public PolicySearchTool(VectorStore vectorStore, com.aegis.ai.admin.AuditTrail audit) {
        this.vectorStore = vectorStore;
        this.audit = audit;
    }

    @Tool(description = "Search the current user's own bank policy documents for an "
            + "answer. Use this for any question about policies, deadlines, limits, or procedures.")
    public String searchPolicies(
            @ToolParam(description = "the policy question to look up") String query,
            ToolContext ctx) {

        Object p = ctx.getContext().get(BankingTools.PRINCIPAL_KEY);
        if (!(p instanceof Principal principal)) {
            throw new AccessDeniedException("No authenticated principal in tool context");
        }
        String tenantId = principal.tenantId();   
        audit.toolCalled("searchPolicies", tenantId);
        BankingTools.status(ctx, "Searching policy documents…");

        var filter = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                .eq("tenantId", tenantId).build();
        var results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(6)                      
                .similarityThreshold(0.1)
                .filterExpression(filter)
                .build());

        log.info("tool.searchPolicies user={} tenant={} hits={}",
                principal.userId(), tenantId, results == null ? 0 : results.size());

        if (results == null || results.isEmpty()) {
            return "No matching policy passages found.";
        }
        return results.stream()
                .map(d -> "- (" + d.getMetadata().getOrDefault("source", "?") + ") " + d.getText())
                .collect(Collectors.joining("\n"));
    }
}
