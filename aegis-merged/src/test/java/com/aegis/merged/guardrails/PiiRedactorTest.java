package com.aegis.merged.guardrails;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @Test
    @DisplayName("A Luhn-valid card number is redacted")
    void luhnValidPanRedacted() {
        assertThat(redactor.redact("My card is 4111 1111 1111 1111")).contains("[REDACTED_PAN]");
    }

    @Test
    @DisplayName("A Luhn-invalid digit run with no card context passes through unredacted")
    void luhnInvalidPanWithoutContextPassesThrough() {
        String out = redactor.redact("Order reference 1234567890123456");
        assertThat(out).doesNotContain("[REDACTED_PAN]").contains("1234567890123456");
    }

    @Test
    @DisplayName("A Luhn-invalid digit run near card-context words is still redacted")
    void luhnInvalidPanNearCardContextIsRedacted() {
        assertThat(redactor.redact("my card number is 1234567890123456")).contains("[REDACTED_PAN]");
        assertThat(redactor.redact("please charge my credit card 1234567890123456")).contains("[REDACTED_PAN]");
    }

    @Test
    @DisplayName("Email addresses are redacted")
    void emailRedacted() {
        assertThat(redactor.redact("Contact me at jane.doe@example.com")).contains("[REDACTED_EMAIL]");
    }

    @Test
    @DisplayName("SSN is redacted with either dash or space separators")
    void ssnRedactedRegardlessOfSeparator() {
        assertThat(redactor.redact("SSN 123-45-6789")).contains("[REDACTED_SSN]");
        assertThat(redactor.redact("SSN 123 45 6789")).contains("[REDACTED_SSN]");
    }

    @Test
    @DisplayName("IBAN is redacted regardless of case")
    void ibanRedactedRegardlessOfCase() {
        assertThat(redactor.redact("IBAN GB82WEST12345698765432")).contains("[REDACTED_IBAN]");
        assertThat(redactor.redact("iban gb82west12345698765432")).contains("[REDACTED_IBAN]");
    }

    @Test
    @DisplayName("Phone numbers are redacted")
    void phoneNumberRedacted() {
        assertThat(redactor.redact("Call me at +1-555-0100")).contains("[REDACTED_PHONE]");
        assertThat(redactor.redact("Call me at 555-0100")).contains("[REDACTED_PHONE]");
    }

    @Test
    @DisplayName("Plain text with no PII is unchanged")
    void plainTextUnchanged() {
        String text = "Your account is in good standing.";
        assertThat(redactor.redact(text)).isEqualTo(text);
    }
}
