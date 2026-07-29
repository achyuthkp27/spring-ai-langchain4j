package com.aegis.lc4j.tools;

import com.aegis.lc4j.admin.AuditTrail;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PolicySearchTool {

    private static final Logger log = LoggerFactory.getLogger(PolicySearchTool.class);

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final AuditTrail audit;
    private final String tenantId;
    private final String userId;
    private final Consumer<String> statusSink;

    public PolicySearchTool(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                            AuditTrail audit, String tenantId, String userId,
                            Consumer<String> statusSink) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.audit = audit;
        this.tenantId = tenantId;
        this.userId = userId;
        this.statusSink = statusSink;
    }

    @Tool("Search the bank's policy documents for an answer. Use this for ANY question "
            + "about policies, deadlines, limits, fees, or procedures.")
    public String searchPolicies(@P("the policy question to look up") String query) {
        audit.toolCalled("searchPolicies", tenantId);
        statusSink.accept("Searching policy documents…");

        Embedding q = embeddingModel.embed(query).content();

        var results = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(q)
                .maxResults(6)
                .minScore(0.1)
                .filter(MetadataFilterBuilder.metadataKey("tenantId").isEqualTo(tenantId))
                .build()).matches();

        log.info("tool.searchPolicies user={} tenant={} hits={}", userId, tenantId, results.size());

        if (results.isEmpty()) {
            return "No matching policy passages found.";
        }
        return results.stream()
                .map(m -> "- (" + m.embedded().metadata().getString("source") + ") "
                        + m.embedded().text())
                .collect(Collectors.joining("\n"));
    }
}
