package com.aegis.lc4j.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingFiltersTest {

    @Test
    void thinkFilterDropsReasoningSpan() {
        var f = new ThinkTagFilter();
        assertEquals("Hello world",
                f.accept("<think>secret reasoning</think>Hello world") + f.flush());
    }

    @Test
    void thinkFilterHandlesTagSplitAcrossChunks() {
        var f = new ThinkTagFilter();
        StringBuilder out = new StringBuilder();
        out.append(f.accept("<thi"));
        out.append(f.accept("nk>hidden"));
        out.append(f.accept(" stuff</thi"));
        out.append(f.accept("nk>visible"));
        out.append(f.flush());
        assertEquals("visible", out.toString());
    }

    @Test
    void thinkFilterPassesPlainTextWithAngleBrackets() {
        var f = new ThinkTagFilter();
        String s = "a < b and b > c";
        assertEquals(s, f.accept(s) + f.flush());
    }

    @Test
    void unterminatedThinkIsDroppedNotLeaked() {
        var f = new ThinkTagFilter();
        assertEquals("", f.accept("<think>never closed") + f.flush());
    }

    @Test
    void safeBoundaryFindsNewlineAndSentenceEnd() {
        assertEquals(6, AssistantController.lastSafeBoundary("line1\nrest"));
        assertEquals(9, AssistantController.lastSafeBoundary("Done now. And then part"));
        assertEquals(-1, AssistantController.lastSafeBoundary("no boundary yet"));
        
        assertEquals(-1, AssistantController.lastSafeBoundary("$2500.00"));
    }

    @Test
    void leakedToolCallDetected() {
        assertTrue(AssistantController.looksLikeLeakedToolCall(
                "{\"name\": \"freezeCard\", \"arguments\": {\"cardId\": \"CRD-1\"}}"));
        assertFalse(AssistantController.looksLikeLeakedToolCall("Your card is frozen."));
    }
}
