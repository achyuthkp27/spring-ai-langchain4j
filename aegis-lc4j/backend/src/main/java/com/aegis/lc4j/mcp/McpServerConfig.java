package com.aegis.lc4j.mcp;

import com.aegis.lc4j.admin.AuditTrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

@Configuration
public class McpServerConfig {

    private static final McpSchema.JsonSchema SEARCH_SCHEMA = new McpSchema.JsonSchema(
            "object",
            Map.of(
                    "tenantId", Map.of("type", "string",
                            "description", "tenant whose policies to search, e.g. achu-bank"),
                    "query", Map.of("type", "string",
                            "description", "the policy question")),
            List.of("tenantId", "query"),
            false, null, null);

    @Bean
    WebMvcSseServerTransportProvider mcpTransport(ObjectMapper objectMapper) {
        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint("/mcp/message")
                .sseEndpoint("/sse")
                .build();
    }

    @Bean
    RouterFunction<ServerResponse> mcpRouter(WebMvcSseServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean
    McpSyncServer mcpServer(WebMvcSseServerTransportProvider transport,
                            EmbeddingStore<TextSegment> store,
                            EmbeddingModel embeddingModel,
                            AuditTrail audit) {
        var searchTool = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("searchPolicies")
                        .description("Search a tenant's bank policy documents (read-only)")
                        .inputSchema(SEARCH_SCHEMA)
                        .build())
                .callHandler((exchange, request) -> {
                    String tenantId = String.valueOf(request.arguments().get("tenantId"));
                    String query = String.valueOf(request.arguments().get("query"));
                    audit.toolCalled("mcp:searchPolicies", tenantId);
                    var q = embeddingModel.embed(query).content();
                    var matches = store.search(EmbeddingSearchRequest.builder()
                            .queryEmbedding(q)
                            .maxResults(6)
                            .minScore(0.1)
                            .filter(MetadataFilterBuilder.metadataKey("tenantId").isEqualTo(tenantId))
                            .build()).matches();
                    String text = matches.isEmpty()
                            ? "No matching policy passages found."
                            : matches.stream()
                                .map(m -> "- (" + m.embedded().metadata().getString("source") + ") "
                                        + m.embedded().text())
                                .collect(Collectors.joining("\n"));
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(text)))
                            .build();
                })
                .build();

        return McpServer.sync(transport)
                .serverInfo("aegis-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(searchTool)
                .build();
    }
}
