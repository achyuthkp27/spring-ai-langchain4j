package com.aegis.lc4j.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;

@Configuration
public class RetrievalConfig {

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password) {
        
        var uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String database = uri.getPath().replaceFirst("/", "");
        return PgVectorEmbeddingStore.builder()
                .host(uri.getHost())
                .port(uri.getPort() == -1 ? 5432 : uri.getPort())
                .database(database)
                .user(user)
                .password(password)
                .table("policy_embeddings")
                .dimension(768)
                .createTable(true)
                .useIndex(true)          
                .indexListSize(100)
                .build();
    }
}
