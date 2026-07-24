package com.aegis.merged.assistant;

import com.aegis.merged.security.AccessDeniedException;
import com.aegis.merged.security.Principal;
import com.aegis.merged.tools.BankingTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Policy search for the unified assistant. CRUCIAL DIFFERENCE from the MCP
 * PolicyTools: the tenant is taken from the authenticated Principal in ToolContext,
 * NEVER from a model-supplied argument. The model cannot ask for another tenant's
 * documents by putting a different tenantId in a tool call — the security boundary
 * is not the model's to choose.
 */
@Component
public class PolicySearchTool {

    private static final Logger log = LoggerFactory.getLogger(PolicySearchTool.class);
    private final VectorStore vectorStore;
    private final com.aegis.merged.admin.AuditTrail audit;

    public PolicySearchTool(VectorStore vectorStore, com.aegis.merged.admin.AuditTrail audit) {
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
        String tenantId = principal.tenantId();   // from identity, not from the model
        audit.toolCalled("searchPolicies", tenantId);
        BankingTools.status(ctx, "Searching policy documents…");

        // Typed filter for defense in depth (tenantId comes from the verified JWT,
        // but never build filter expressions by string concatenation).
        var filter = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                .eq("tenantId", tenantId).build();
        var results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(6)                      // corpus grew 5x (12 docs / ~48 chunks); 4 was tuned for 3 docs
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
