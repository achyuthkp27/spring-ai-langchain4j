package com.aegis.merged.rag;

import com.aegis.merged.guardrails.GuardrailAdvisor;
import com.aegis.merged.security.CurrentUser;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Grounded RAG endpoint. Tenant isolation is enforced at retrieval time via a
 * server-side filter expression built from the authenticated tenant — NEVER from
 * user input. This is the mitigation for OWASP LLM08 (vector/embedding weaknesses).
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ChatClient ragClient;
    private final org.springframework.ai.vectorstore.VectorStore vectorStore;

    public RagController(@Qualifier("ragClient") ChatClient ragClient,
                         org.springframework.ai.vectorstore.VectorStore vectorStore) {
        this.ragClient = ragClient;
        this.vectorStore = vectorStore;
    }

    public record AskRequest(String question) {
    }

    public record AskResponse(String tenantId, String answer) {
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        // Tenant comes from the verified JWT claim, never from user input.
        String tenantId = CurrentUser.get().tenantId();
        // Typed builder, NOT string concatenation — see PolicySearchTool/PolicyTools for the
        // exact same pattern and its rationale. tenantId is a JWT claim so this is defense in
        // depth rather than the only barrier, but a hand-built "tenantId == '" + tenantId + "'"
        // string is a filter-injection sink the moment that assumption is wrong even once.
        var filter = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                .eq("tenantId", tenantId).build();
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(6)
                        // Local embedding models (nomic) score lower than hosted ones;
                        // a lenient threshold improves recall. Tenant isolation is
                        // unaffected — the filter is a hard SQL WHERE, not a similarity knob.
                        .similarityThreshold(0.1)
                        .filterExpression(filter)
                        .build())
                .build();

        String answer = ragClient.prompt()
                .advisors(qaAdvisor)
                .advisors(a -> a.param(GuardrailAdvisor.TENANT_PARAM, tenantId))
                .user(request.question())
                .call()
                .content();

        return new AskResponse(tenantId, answer);
    }
}
