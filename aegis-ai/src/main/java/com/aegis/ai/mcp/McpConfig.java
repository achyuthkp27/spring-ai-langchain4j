package com.aegis.ai.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the read-only PolicyTools with the MCP server so any MCP client can
 * discover and call them. In production this endpoint is protected with Spring
 * Security OAuth2 (scoped tokens) — see README "Production hardening".
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
