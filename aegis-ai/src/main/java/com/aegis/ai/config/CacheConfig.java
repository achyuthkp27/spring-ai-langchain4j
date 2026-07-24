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

/**
 * Wires the {@link SemanticCache} with its OWN embedding model, deliberately
 * different from the RAG retrieval embedding — LOCALLY. Why two models? They
 * answer different questions:
 *  - RAG (nomic-embed-text): "which documents are relevant to this query?" — an
 *    asymmetric query→document retrieval task.
 *  - Cache (all-minilm): "is this the SAME question as one I've already answered?" —
 *    a symmetric question↔question similarity task.
 *
 * Using nomic for the cache caused the observed over-match ("what is dispute?"
 * colliding with the deadline answer): nomic separates paraphrases from different
 * questions by only ~0.09 cosine. all-minilm (STS-trained, symmetric) separates
 * them by ~0.27 on our corpus, which is what makes a safe threshold possible.
 *
 * That dedicated small model only exists because Ollama makes a second local model
 * free to run. In the cloud (openai-embeddings profile) there is no free second
 * model — every call is a paid, network-hop API request — and OpenAI's embeddings
 * are strong generalists at both asymmetric and symmetric similarity, so the cache
 * reuses the SAME EmbeddingModel bean pgvector/RAG already autowire.
 */
@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.model", name = "embedding",
            havingValue = "ollama", matchIfMissing = true)
    SemanticCache ollamaSemanticCache(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
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
