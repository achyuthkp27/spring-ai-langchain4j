package com.aegis.merged.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThinkTagFilterTest {

    @Test
    void dropsReasoningSpan() {
        var f = new ThinkTagFilter();
        assertEquals("Hello world",
                f.accept("<think>secret reasoning</think>Hello world") + f.flush());
    }

    @Test
    void handlesTagSplitAcrossChunks() {
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
    void passesPlainTextWithAngleBrackets() {
        var f = new ThinkTagFilter();
        String s = "a < b and b > c";
        assertEquals(s, f.accept(s) + f.flush());
    }

    @Test
    void unterminatedThinkIsDroppedNotLeaked() {
        var f = new ThinkTagFilter();
        assertEquals("", f.accept("<think>never closed") + f.flush());
    }
}
