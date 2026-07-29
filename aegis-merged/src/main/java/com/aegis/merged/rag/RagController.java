package com.aegis.merged.rag;

import com.aegis.merged.assistant.ChatInputValidation;
import com.aegis.merged.guardrails.GuardrailAdvisor;
import com.aegis.merged.security.CurrentUser;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final ChatClient ragClient;
    private final VectorStore vectorStore;

    public RagController(@Qualifier("ragClient") ChatClient ragClient,
                         VectorStore vectorStore) {
        this.ragClient = ragClient;
        this.vectorStore = vectorStore;
    }

    public record AskRequest(String conversationId, String question) {
        public AskRequest {
            conversationId = ChatInputValidation.normalizeAndValidateConversationId(conversationId);
            ChatInputValidation.validateMessage(question);
        }
    }

    public record AskResponse(String tenantId, String answer) {
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        var principal = CurrentUser.get();
        String tenantId = principal.tenantId();
        String memoryKey = tenantId + ":" + principal.userId() + ":" + request.conversationId();

        var filter = new FilterExpressionBuilder().eq("tenantId", tenantId).build();
        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(6)
                        .similarityThreshold(0.1)
                        .filterExpression(filter)
                        .build())
                .build();

        String answer = ragClient.prompt()
                .advisors(qaAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey)
                        .param(GuardrailAdvisor.TENANT_PARAM, tenantId)
                        .param(GuardrailAdvisor.USER_PARAM, principal.userId()))
                .user(request.question())
                .call()
                .content();

        return new AskResponse(tenantId, answer);
    }
}
