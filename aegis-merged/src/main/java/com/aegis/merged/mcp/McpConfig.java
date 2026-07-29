package com.aegis.merged.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    ToolCallbackProvider aegisMcpTools(PolicyTools policyTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(policyTools)
                .build();
    }
}
