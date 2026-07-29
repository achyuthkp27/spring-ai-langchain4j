package com.aegis.merged.assistant;

final class ThinkTagFilter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder held = new StringBuilder();
    private boolean insideThink;

    String accept(String chunk) {
        held.append(chunk);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (insideThink) {
                int close = held.indexOf(CLOSE);
                if (close < 0) {
                    
                    keepOnlyPartialSuffix(CLOSE);
                    return out.toString();
                }
                held.delete(0, close + CLOSE.length());
                insideThink = false;
            } else {
                int open = held.indexOf(OPEN);
                if (open < 0) {
                    
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
