package com.aegis.lc4j.guardrails;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PiiRedactor {

    private static final Pattern PAN = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");

    public String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String out = input;
        out = SSN.matcher(out).replaceAll("[REDACTED_SSN]");
        out = IBAN.matcher(out).replaceAll("[REDACTED_IBAN]");
        out = PAN.matcher(out).replaceAll("[REDACTED_PAN]");
        out = EMAIL.matcher(out).replaceAll("[REDACTED_EMAIL]");
        return out;
    }
}
