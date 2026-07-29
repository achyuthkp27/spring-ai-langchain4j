package com.aegis.lc4j.rag;

import com.aegis.lc4j.resilience.LlmResilience;
import com.aegis.lc4j.security.CurrentUser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final String RAG_PROMPT = """
            You answer customer questions ONLY from the provided policy context.
            Cite the source document. If the context doesn't contain the answer,
            say you don't have that in the available documents — never guess.
            """;

    interface RagAssistant {
        @dev.langchain4j.service.SystemMessage(RAG_PROMPT)
        String answer(String question);
    }

    private final ChatModel chatModel;
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final LlmResilience resilience;

    public RagController(ChatModel chatModel, EmbeddingStore<TextSegment> store,
                         EmbeddingModel embeddingModel, LlmResilience resilience) {
        this.chatModel = chatModel;
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.resilience = resilience;
    }

    public record AskRequest(String question) {
    }

    private EmbeddingStoreContentRetriever retrieverFor(String tenantId) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(6)
                .minScore(0.1)
                .filter(MetadataFilterBuilder.metadataKey("tenantId").isEqualTo(tenantId))
                .build();
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody AskRequest req) {
        String tenantId = CurrentUser.get().tenantId();
        RagAssistant assistant = AiServices.builder(RagAssistant.class)
                .chatModel(chatModel)
                .contentRetriever(retrieverFor(tenantId))
                .build();
        String answer = resilience.call(() -> assistant.answer(req.question()));
        return Map.of("answer", answer);
    }

    @PostMapping("/ask-advanced")
    public Map<String, String> askAdvanced(@RequestBody AskRequest req) {
        String tenantId = CurrentUser.get().tenantId();
        RagAssistant assistant = AiServices.builder(RagAssistant.class)
                .chatModel(chatModel)
                .retrievalAugmentor(DefaultRetrievalAugmentor.builder()
                        .queryTransformer(new CompressingQueryTransformer(chatModel))
                        .contentRetriever(retrieverFor(tenantId))
                        .build())
                .build();
        String answer = resilience.call(() -> assistant.answer(req.question()));
        return Map.of("answer", answer);
    }
}
