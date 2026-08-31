package com.aegis.lc4j.guardrails;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class InjectionScreen {

    private static final List<String> SUSPICIOUS = List.of(
            "disregard the above",
            "you are now",
            "developer mode",
            "jailbreak",
            "act as an unrestricted"
    );

    private static final List<Pattern> SUSPICIOUS_PATTERNS = List.of(
            Pattern.compile(
                "\\b(ignore|disregard|forget|override|bypass)\\b.{0,30}\\b(instructions?|rules?|prompts?|guidelines?|guardrails?)\\b"),
            Pattern.compile(
                "\\b(reveal|print|show|output|repeat|leak|expose)\\b.{0,30}\\b(system prompt|hidden prompt|instructions|initial prompt)\\b"),
            Pattern.compile(
                "\\b(pretend|act|roleplay)\\b.{0,20}\\b(you (are|have) no|without (any )?(rules|restrictions|limits))\\b")
    );

    public record Result(boolean flagged, String matched) {
    }

    public Result screen(String input) {
        if (input == null) {
            return new Result(false, null);
        }
        String lower = input.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String pattern : SUSPICIOUS) {
            if (lower.contains(pattern)) {
                return new Result(true, pattern);
            }
        }
        for (var p : SUSPICIOUS_PATTERNS) {
            var m = p.matcher(lower);
            if (m.find()) {
                return new Result(true, m.group());
            }
        }
        return new Result(false, null);
    }
}
