package com.aegis.merged.assistant;

import com.aegis.merged.admin.AuditTrail;
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
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PolicySearchTool {

    private static final Logger log = LoggerFactory.getLogger(PolicySearchTool.class);
    private final VectorStore vectorStore;
    private final AuditTrail audit;

    public PolicySearchTool(VectorStore vectorStore, AuditTrail audit) {
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

        var filter = new FilterExpressionBuilder().eq("tenantId", tenantId).build();
        var results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(6)                      
                .similarityThreshold(0.1)
                .filterExpression(filter)
                .build());

        log.info("tool.searchPolicies user={} tenant={} hits={}",
                principal.userId(), tenantId, results == null ? 0 : results.size());

        if (results == null || results.isEmpty()) {
            BankingTools.markFailed(ctx);
            return "No matching policy passages found.";
        }

        var citations = new java.util.LinkedHashMap<String, BankingTools.Citation>();
        for (var d : results) {
            String source = String.valueOf(d.getMetadata().getOrDefault("source", "?"));
            citations.putIfAbsent(source, new BankingTools.Citation(source, snippet(d.getText())));
        }
        BankingTools.emitCitations(ctx, java.util.List.copyOf(citations.values()));

        return results.stream()
                .map(d -> "- (" + d.getMetadata().getOrDefault("source", "?") + ") " + d.getText())
                .collect(Collectors.joining("\n"));
    }

    private static String snippet(String text) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
