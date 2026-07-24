package com.aegis.lc4j.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Manual model wiring (no LC4j Spring starter): we need FOUR distinct models —
 * a streaming chat model for the assistant, a blocking chat model for the scope
 * classifier / query rewriting, the retrieval embedding model (nomic, 768-dim)
 * and a separate symmetric-similarity model for the semantic cache (all-minilm).
 *
 * qwen3.5 is a thinking model; think=false keeps tool-calling latency sane and
 * stops reasoning tokens from leaking into the stream.
 */
@Configuration
public class ModelConfig {

    @Bean
    StreamingChatModel streamingChatModel(
            @Value("${aegis.ollama.base-url}") String baseUrl,
            @Value("${aegis.ollama.chat-model}") String model,
            @Value("${aegis.ollama.temperature}") Double temperature,
            @Value("${aegis.ollama.num-predict}") Integer numPredict,
            @Value("${aegis.ollama.think}") Boolean think) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .numPredict(numPredict)
                .think(think)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    @Primary
    ChatModel chatModel(
            @Value("${aegis.ollama.base-url}") String baseUrl,
            @Value("${aegis.ollama.chat-model}") String model,
            @Value("${aegis.ollama.temperature}") Double temperature,
            @Value("${aegis.ollama.num-predict}") Integer numPredict,
            @Value("${aegis.ollama.think}") Boolean think) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .numPredict(numPredict)
                .think(think)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /** Deterministic 4-token classifier variant of the chat model for the ScopeGate. */
    @Bean
    @Qualifier("classifierModel")
    ChatModel classifierModel(
            @Value("${aegis.ollama.base-url}") String baseUrl,
            @Value("${aegis.ollama.chat-model}") String model,
            @Value("${aegis.ollama.think}") Boolean think) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(0.0)
                .numPredict(4)
                .think(think)
                .timeout(Duration.ofSeconds(20))
                .build();
    }

    @Bean
    @Primary
    EmbeddingModel embeddingModel(
            @Value("${aegis.ollama.base-url}") String baseUrl,
            @Value("${aegis.ollama.embedding-model}") String model) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /** Dedicated symmetric sentence-similarity model for the semantic cache. */
    @Bean
    @Qualifier("cacheEmbeddingModel")
    EmbeddingModel cacheEmbeddingModel(
            @Value("${aegis.ollama.base-url}") String baseUrl,
            @Value("${aegis.ollama.cache-embedding-model}") String model) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}
