package com.aegis.lc4j.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final SemanticCache semanticCache;

    public IngestionService(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                            SemanticCache semanticCache) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.semanticCache = semanticCache;
    }

    public int ingestAll() {

        semanticCache.clear();

        try {
            store.removeAll(MetadataFilterBuilder.metadataKey("tenantId").isNotEqualTo("__none__"));
            log.info("ingest cleared existing chunks");
        } catch (Exception e) {
            log.warn("ingest clear step skipped: {}", e.getMessage());
        }

        var ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1600, 200))   
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();

        var resolver = new PathMatchingResourcePatternResolver();
        int total = 0;
        try {
            Resource[] resources = resolver.getResources("classpath:documents/*/*.md");
            for (Resource resource : resources) {
                String tenantId = tenantOf(resource);
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Document doc = Document.from(text, Metadata.from(Map.of(
                        "tenantId", tenantId,
                        "docType", docTypeOf(resource),
                        "source", String.valueOf(resource.getFilename()))));
                ingestor.ingest(doc);
                log.info("ingest tenant={} source={}", tenantId, resource.getFilename());
                total++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Ingestion failed", e);
        }
        log.info("ingest complete documents={}", total);
        return total;
    }

    public void add(String tenantId, String docType, String text) {
        TextSegment segment = TextSegment.from(text, Metadata.from(Map.of(
                "tenantId", tenantId, "docType", docType, "source", "adhoc")));
        store.add(embeddingModel.embed(segment).content(), segment);
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
