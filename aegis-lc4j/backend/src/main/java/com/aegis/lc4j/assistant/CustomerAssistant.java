package com.aegis.lc4j.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * The AiServices contract for the streaming customer assistant. One instance is
 * built per request (see AssistantService) with the caller's identity baked into
 * the tools and the memory id derived from the verified JWT.
 */
public interface CustomerAssistant {

    TokenStream chat(@MemoryId String memoryId, @UserMessage String message);
}
