package com.aegis.merged.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the read-only PolicyTools with the MCP server so any MCP client can
 * discover and call them. The transport (/sse, /mcp/**) requires a valid JWT —
 * see SecurityConfig — so this is no longer reachable anonymously; a real deployment
 * should still scope it further (a dedicated MCP-client credential/role) rather than
 * accepting any authenticated app JWT.
 */
@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider aegisMcpTools(PolicyTools policyTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(policyTools)
                .build();
    }
}
