package com.aegis.ai.config;

import com.aegis.ai.rag.SemanticCache;
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
    @ConditionalOnProperty(prefix = "spring.ai.model", name = "embedding",
            havingValue = "ollama", matchIfMissing = true)
    SemanticCache ollamaSemanticCache(
            @Value("${spring.ai.ollama.base-url:http:
            @Value("${aegis.cache.embedding-model:all-minilm}") String cacheModel) {

        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        OllamaEmbeddingModel cacheEmbedding = OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaEmbeddingOptions.builder().model(cacheModel).build())
                .build();
        return new SemanticCache(cacheEmbedding);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.model", name = "embedding", havingValue = "openai")
    SemanticCache cloudSemanticCache(EmbeddingModel primaryEmbeddingModel) {
        return new SemanticCache(primaryEmbeddingModel);
    }
}
