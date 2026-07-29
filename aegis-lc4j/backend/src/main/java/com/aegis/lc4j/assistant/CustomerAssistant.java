package com.aegis.lc4j.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface CustomerAssistant {

    TokenStream chat(@MemoryId String memoryId, @UserMessage String message);
}
