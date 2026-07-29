package com.aegis.merged.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import com.aegis.merged.guardrails.GuardrailAdvisor;
import com.aegis.merged.security.CurrentUser;
import com.aegis.merged.security.Principal;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class AdvancedRagController {

    private final ChatClient ragClient;
    private final ChatClient.Builder builder;
    private final VectorStore vectorStore;
    private final SemanticCache semanticCache;

    public AdvancedRagController(@Qualifier("ragClient") ChatClient ragClient,
                                 ChatClient.Builder builder,
                                 VectorStore vectorStore,
                                 SemanticCache semanticCache) {
        this.ragClient = ragClient;
        this.builder = builder;
        this.vectorStore = vectorStore;
        this.semanticCache = semanticCache;
    }

    public record AskRequest(String conversationId, String question) {
        public AskRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
        }
    }

    public record AskResponse(String tenantId, String answer, boolean cached, long elapsedMs) {
    }

    @PostMapping("/ask-advanced")
    public AskResponse ask(@RequestBody AskRequest request) {
        long start = System.nanoTime();
        Principal principal = CurrentUser.get();
        String tenantId = principal.tenantId();

        var hit = semanticCache.lookup(tenantId, request.question());
        if (hit.isPresent()) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new AskResponse(tenantId, hit.get().answer(), true, ms);
        }

        var retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(6)
                .similarityThreshold(0.1)
                .filterExpression(() -> new FilterExpressionBuilder().eq("tenantId", tenantId).build())
                .build();

        var ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(builder.build().mutate())
                        .build())
                .documentRetriever(retriever)
                .build();

        String memoryKey = tenantId + ":" + principal.userId() + ":" + request.conversationId();

        String answer = ragClient.prompt()
                .advisors(ragAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey)
                        .param(GuardrailAdvisor.TENANT_PARAM, tenantId)
                        .param(GuardrailAdvisor.USER_PARAM, principal.userId()))
                .user(request.question())
                .call()
                .content();

        if (answer != null && !answer.toLowerCase().contains("i don't have that")) {
            semanticCache.put(tenantId, request.question(), answer);
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        return new AskResponse(tenantId, answer, false, ms);
    }
}
