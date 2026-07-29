package com.aegis.merged.admin;

import com.aegis.merged.domain.BankingService;
import com.aegis.merged.rag.SemanticCache;
import com.aegis.merged.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PrivacyControllerTest {

    private final PrivacyController controller = new PrivacyController(
            mock(JdbcTemplate.class), new BankingService(), mock(AuditTrail.class), mock(SemanticCache.class));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String tenantId, String userId) {
        var principal = new Principal(userId, tenantId, Set.of("platform:admin"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    @Test
    @DisplayName("A blank tenantId is rejected instead of silently erasing nothing "
            + "while reporting success")
    void blankTenantIdIsRejected() {
        authenticateAs("achu-bank", "admin1");
        assertThatThrownBy(() -> controller.erase("", "some-user"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.erase(null, "some-user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A blank userId is rejected")
    void blankUserIdIsRejected() {
        authenticateAs("achu-bank", "admin1");
        assertThatThrownBy(() -> controller.erase("achu-bank", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
