package com.aegis.merged.guardrails;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
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
        out = redactPan(out);
        out = EMAIL.matcher(out).replaceAll("[REDACTED_EMAIL]");
        return out;
    }

    private static String redactPan(String input) {
        Matcher m = PAN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digitsOnly = m.group().replaceAll("[ -]", "");
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    luhnValid(digitsOnly) ? "[REDACTED_PAN]" : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
