package com.aegis.merged.config;

import com.aegis.merged.guardrails.LlmGuard;
import com.aegis.merged.security.AccessDeniedException;
import com.aegis.merged.security.UnauthenticatedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("No authenticated principal maps to 401, not 403")
    void unauthenticatedIsFourZeroOne() {
        var response = handler.onUnauthenticated(new UnauthenticatedException("No authenticated user"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("An authenticated but unauthorized principal maps to 403")
    void accessDeniedIsFourZeroThree() {
        var response = handler.onAccessDenied(new AccessDeniedException("Not your tenant."));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Bad input maps to 400 with the validation message")
    void badInputIsFourHundred() {
        var response = handler.onBadInput(new IllegalArgumentException("message must be 4000 characters or fewer."));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "message must be 4000 characters or fewer.");
    }

    @Test
    @DisplayName("LLM unavailability maps to 503, not 500")
    void llmUnavailableIsFiveOhThree() {
        var response = handler.onLlmUnavailable(new LlmGuard.LlmUnavailableException("busy"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("An unexpected exception maps to 500 without leaking its message")
    void unexpectedIsFiveHundredAndGeneric() {
        var response = handler.onUnexpected(new RuntimeException("npe at BankingService.java:217"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "Something went wrong. Please try again.");
    }
}
