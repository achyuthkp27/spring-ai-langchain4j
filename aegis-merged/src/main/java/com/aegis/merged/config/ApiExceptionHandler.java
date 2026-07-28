package com.aegis.merged.config;

import com.aegis.merged.security.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Without this, every non-streaming REST endpoint had no mapping at all from a domain
 * exception to an HTTP status: AccessDeniedException (thrown throughout the admin/tenant-scope
 * checks and every BankingTools authz check) fell through to a raw 500, identical to an actual
 * server bug, and a malformed {@code Instant.parse} input (e.g. AdminController's
 * {@code /audit/query?from=}) did the same. The streaming endpoint (AssistantController) has
 * its own {@code onErrorResume} for the same reason — this is the equivalent for every request
 * that returns a plain JSON body instead of an SSE stream.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> onAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    public ResponseEntity<Map<String, String>> onBadInput(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage() == null ? "Invalid request." : e.getMessage()));
    }
}
