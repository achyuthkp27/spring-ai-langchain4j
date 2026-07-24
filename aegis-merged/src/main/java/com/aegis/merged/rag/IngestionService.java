package com.aegis.merged.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 ingestion (a lightweight stand-in for the full Spring Batch ETL):
 * reads per-tenant Markdown policy docs, splits them into chunks, stamps each
 * chunk with tenantId + docType metadata, and writes to the pgvector store.
 *
 * <p>The tenantId metadata is the load-bearing security control: retrieval later
 * filters on it server-side so a tenant can never retrieve another tenant's docs.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;
    private final SemanticCache semanticCache;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public IngestionService(VectorStore vectorStore, SemanticCache semanticCache) {
        this.vectorStore = vectorStore;
        this.semanticCache = semanticCache;
    }

    /** Ingests classpath:documents/{tenant}/*.md, tagging every chunk with its tenant. */
    public int ingestAll() {
        // Policy docs are changing → invalidate the semantic cache so no stale
        // answer can be served after an update.
        semanticCache.clear();
        // Idempotent: drop any previously-ingested chunks so re-running doesn't
        // create duplicates that skew retrieval ranking.
        try {
            var all = vectorStore.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder()
                    .query("*").topK(10_000).similarityThreshold(0.0).build());
            if (all != null && !all.isEmpty()) {
                vectorStore.delete(all.stream().map(org.springframework.ai.document.Document::getId).toList());
                log.info("ingest cleared existing chunks={}", all.size());
            }
        } catch (Exception e) {
            log.warn("ingest clear step skipped: {}", e.getMessage());
        }
        var resolver = new PathMatchingResourcePatternResolver();
        int total = 0;
        try {
            Resource[] resources = resolver.getResources("classpath:documents/*/*.md");
            List<Document> chunks = new ArrayList<>();
            for (Resource resource : resources) {
                String tenantId = tenantOf(resource);
                var config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withAdditionalMetadata("tenantId", tenantId)
                        .withAdditionalMetadata("docType", docTypeOf(resource))
                        .withAdditionalMetadata("source", resource.getFilename())
                        .build();
                List<Document> docs = new MarkdownDocumentReader(resource, config).get();
                List<Document> split = splitter.apply(docs);
                // TokenTextSplitter drops metadata by default on some versions; re-stamp defensively.
                for (Document d : split) {
                    d.getMetadata().putIfAbsent("tenantId", tenantId);
                    d.getMetadata().putIfAbsent("docType", docTypeOf(resource));
                    d.getMetadata().putIfAbsent("source", resource.getFilename());
                }
                chunks.addAll(split);
                log.info("ingest tenant={} source={} chunks={}", tenantId, resource.getFilename(), split.size());
            }
            if (!chunks.isEmpty()) {
                vectorStore.add(chunks);
                total = chunks.size();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Ingestion failed", e);
        }
        log.info("ingest complete totalChunks={}", total);
        return total;
    }

    /** Directly add a document for a tenant (used by tenant-isolation tests). */
    public void add(String tenantId, String docType, String text) {
        var doc = new Document(text, Map.of("tenantId", tenantId, "docType", docType, "source", "adhoc"));
        vectorStore.add(List.of(doc));
    }

    private String tenantOf(Resource resource) {
        try {
            String path = resource.getURL().getPath();
            String[] parts = path.split("/documents/");
            return parts[1].split("/")[0];
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String docTypeOf(Resource resource) {
        String name = resource.getFilename() == null ? "" : resource.getFilename();
        if (name.contains("kyc")) return "kyc";
        if (name.contains("dispute")) return "dispute-policy";
        return "policy";
    }
}
