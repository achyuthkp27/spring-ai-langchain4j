package com.aegis.merged.config;

import com.aegis.merged.guardrails.LlmGuard;
import com.aegis.merged.rag.SemanticCache;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.model", name = "embedding",havingValue = "ollama", matchIfMissing = true)
    SemanticCache ollamaSemanticCache(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${aegis.cache.embedding-model:all-minilm}") String cacheModel,
            @Value("${aegis.cache.similarity-threshold:0.62}") double similarityThreshold,
            LlmGuard llmGuard) {

        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        OllamaEmbeddingModel cacheEmbedding = OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaEmbeddingOptions.builder().model(cacheModel).build())
                .build();
        return new SemanticCache(cacheEmbedding, similarityThreshold, llmGuard);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.model", name = "embedding", havingValue = "openai")
    SemanticCache cloudSemanticCache(EmbeddingModel primaryEmbeddingModel,
            @Value("${aegis.cache.similarity-threshold:0.62}") double similarityThreshold,
            LlmGuard llmGuard) {
        return new SemanticCache(primaryEmbeddingModel, similarityThreshold, llmGuard);
    }
}
