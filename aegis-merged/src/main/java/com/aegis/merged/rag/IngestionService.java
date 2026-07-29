package com.aegis.merged.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Transactional
    public int ingestAll() {
        semanticCache.clear();

        var resolver = new PathMatchingResourcePatternResolver();
        int total = 0;
        try {
            Resource[] resources = resolver.getResources("classpath:documents/*/*.md");
            Map<String, List<Document>> chunksByTenant = new LinkedHashMap<>();
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
                for (Document d : split) {
                    d.getMetadata().putIfAbsent("tenantId", tenantId);
                    d.getMetadata().putIfAbsent("docType", docTypeOf(resource));
                    d.getMetadata().putIfAbsent("source", resource.getFilename());
                }
                chunksByTenant.computeIfAbsent(tenantId, k -> new ArrayList<>()).addAll(split);
                log.info("ingest tenant={} source={} chunks={}", tenantId, resource.getFilename(), split.size());
            }

            var filterBuilder = new FilterExpressionBuilder();
            for (String tenantId : chunksByTenant.keySet()) {
                vectorStore.delete(filterBuilder.eq("tenantId", tenantId).build());
            }
            log.info("ingest cleared existing chunks for tenants={}", chunksByTenant.keySet());

            for (List<Document> chunks : chunksByTenant.values()) {
                if (!chunks.isEmpty()) {
                    vectorStore.add(chunks);
                    total += chunks.size();
                }
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
