package com.aegis.lc4j.assistant;

/**
 * Stateful streaming filter that drops qwen-style <think>…</think> reasoning
 * spans. Defence-in-depth: the model runs with think=false, but if reasoning
 * text ever leaks it must not reach the client. Handles tags split across
 * token chunks by holding back a partial "<thi" style suffix until resolved.
 */
final class ThinkTagFilter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder held = new StringBuilder();
    private boolean insideThink;

    /** Feed one raw chunk; returns the text safe to pass downstream (possibly empty). */
    String accept(String chunk) {
        held.append(chunk);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (insideThink) {
                int close = held.indexOf(CLOSE);
                if (close < 0) {
                    // Still inside reasoning — drop everything except a possible partial close tag.
                    keepOnlyPartialSuffix(CLOSE);
                    return out.toString();
                }
                held.delete(0, close + CLOSE.length());
                insideThink = false;
            } else {
                int open = held.indexOf(OPEN);
                if (open < 0) {
                    // Emit everything except a possible partial open tag at the end.
                    int hold = partialSuffixLength(OPEN);
                    out.append(held, 0, held.length() - hold);
                    held.delete(0, held.length() - hold);
                    return out.toString();
                }
                out.append(held, 0, open);
                held.delete(0, open + OPEN.length());
                insideThink = true;
            }
        }
    }

    /** Anything still held that is not a partial tag (call at end of stream). */
    String flush() {
        if (insideThink) {
            held.setLength(0);
            return "";
        }
        String rest = held.toString();
        held.setLength(0);
        return rest;
    }

    private void keepOnlyPartialSuffix(String tag) {
        int hold = partialSuffixLength(tag);
        held.delete(0, held.length() - hold);
    }

    /** Length of the longest strict-prefix of {@code tag} that ends the buffer. */
    private int partialSuffixLength(String tag) {
        int max = Math.min(tag.length() - 1, held.length());
        for (int len = max; len > 0; len--) {
            boolean match = true;
            int start = held.length() - len;
            for (int i = 0; i < len; i++) {
                if (held.charAt(start + i) != tag.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) return len;
        }
        return 0;
    }
}
