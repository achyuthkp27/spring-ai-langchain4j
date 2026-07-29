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
