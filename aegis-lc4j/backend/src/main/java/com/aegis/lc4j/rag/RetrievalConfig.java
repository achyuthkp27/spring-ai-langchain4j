package com.aegis.lc4j.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * pgvector store for policy chunks. Same Postgres instance as chat memory
 * (pgvector/pgvector:pg16 from compose.yaml). 768 dims = nomic-embed-text.
 */
@Configuration
public class RetrievalConfig {

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password) {
        // jdbc:postgresql://host:port/db → host/port/db
        var uri = java.net.URI.create(jdbcUrl.substring("jdbc:".length()));
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
                .useIndex(true)          // HNSW-style ivfflat index for cosine search
                .indexListSize(100)
                .build();
    }
}
