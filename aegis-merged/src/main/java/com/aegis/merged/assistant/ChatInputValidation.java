package com.aegis.merged.assistant;

import java.util.regex.Pattern;

public final class ChatInputValidation {

    public static final int MAX_MESSAGE_LENGTH = 4000;
    public static final int MAX_CONVERSATION_ID_LENGTH = 128;
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    private ChatInputValidation() {
    }

    public static String normalizeAndValidateConversationId(String conversationId) {
        String normalized = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId;
        if (normalized.length() > MAX_CONVERSATION_ID_LENGTH
                || !CONVERSATION_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "conversationId must be alphanumeric (._- allowed), max "
                            + MAX_CONVERSATION_ID_LENGTH + " characters.");
        }
        return normalized;
    }

    public static void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank.");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must be " + MAX_MESSAGE_LENGTH + " characters or fewer.");
        }
    }
}
