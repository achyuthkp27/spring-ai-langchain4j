package com.aegis.merged.tools;

import com.aegis.merged.admin.AuditTrail;
import com.aegis.merged.domain.BankingService;
import com.aegis.merged.security.JwtService;
import com.aegis.merged.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * The freeze-card confirmation gate (added after a live demo showed the assistant freezing
 * ALL of a customer's cards from an ambiguous "freeze my card" with no id given) — verifies
 * a mutating tool never executes without an explicit confirmed=true, and executes exactly
 * once that's set.
 */
class BankingToolsTest {

    private BankingTools tools;
    private Principal demoUser;

    @BeforeEach
    void setUp() {
        BankingService banking = new BankingService();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        tools = new BankingTools(banking, new AuditTrail(jdbc), new com.aegis.merged.kyc.MockKycProvider());
        // Sourced from JwtService.permissionsFor so this test never drifts from the real
        // "customer" role's permission set the way it silently did before (missing
        // money:transfer/account:write/profile:write caused false AccessDenied failures here).
        demoUser = new Principal("demo-user", "achu-bank", JwtService.permissionsFor("customer"));
    }

    private ToolContext ctxFor(Principal p) {
        return new ToolContext(Map.of(BankingTools.PRINCIPAL_KEY, p));
    }

    @Test
    @DisplayName("freezeCard without confirmed=true does NOT freeze the card")
    void freezeRequiresConfirmation() {
        String result = tools.freezeCard("CRD-7001", "lost", null, ctxFor(demoUser));

        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
        // Confirm the card is still active — the earlier request must not have executed.
        String status = tools.listCards("ACC-1001", ctxFor(demoUser));
        assertThat(status).contains("CRD-7001").contains("ACTIVE");
    }

    @Test
    @DisplayName("freezeCard with confirmed=true actually freezes the card")
    void freezeExecutesOnceConfirmed() {
        String result = tools.freezeCard("CRD-7001", "lost", true, ctxFor(demoUser));

        assertThat(result).contains("FROZEN");
        String status = tools.listCards("ACC-1001", ctxFor(demoUser));
        assertThat(status).contains("CRD-7001").contains("FROZEN");
    }

    @Test
    @DisplayName("A card never mentioned in the conversation is untouched by freezing a different one")
    void onlyTheConfirmedCardIsFrozen() {
        tools.freezeCard("CRD-7001", "lost", true, ctxFor(demoUser));

        String otherStatus = tools.listCards("ACC-1001", ctxFor(demoUser));
        assertThat(otherStatus).contains("CRD-7002").doesNotContain("CRD-7002 CREDIT ****8830 (FROZEN)");
    }

    @Test
    @DisplayName("requestCardReplacement without confirmed=true creates no approval")
    void replacementRequiresConfirmation() {
        String result = tools.requestCardReplacement("CRD-7001", "lost", null, ctxFor(demoUser));
        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
    }

    @Test
    @DisplayName("transferBetweenOwnAccounts without confirmed=true moves no money")
    void transferRequiresConfirmation() {
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "100", null, ctxFor(demoUser));
        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("transferBetweenOwnAccounts posts a real double-entry movement that nets to zero")
    void transferMovesMoneyBothWays() {
        tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "100", true, ctxFor(demoUser));

        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2400.00");
        assertThat(tools.lookupBalance("ACC-1002", ctxFor(demoUser))).contains("15850.25");
    }

    @Test
    @DisplayName("transferBetweenOwnAccounts rejects a transfer larger than the source balance")
    void transferRejectsInsufficientFunds() {
        // Below the per-transfer cap but above the account balance, so this exercises the
        // domain-level funds check specifically, not the cap check (see transferRejectsAmountAboveCap).
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "10000", true, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("insufficient");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("Negative or non-positive transfer amounts are rejected, not reversed")
    void transferRejectsNonPositiveAmount() {
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "-100", true, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("positive");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("Transfers above the per-transfer cap are rejected")
    void transferRejectsAmountAboveCap() {
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "999999", true, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("limit");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("A customer cannot transfer into an account they don't own")
    void transferRejectsForeignDestination() {
        assertThrows(com.aegis.merged.security.AccessDeniedException.class,
                () -> tools.transferBetweenOwnAccounts("ACC-1001", "ACC-9001", "10", true, ctxFor(demoUser)));
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("unfreezeCard reverses freezeCard")
    void unfreezeReversesFreeze() {
        tools.freezeCard("CRD-7001", "lost", true, ctxFor(demoUser));
        tools.unfreezeCard("CRD-7001", true, ctxFor(demoUser));
        assertThat(tools.listCards("ACC-1001", ctxFor(demoUser))).contains("CRD-7001").contains("ACTIVE");
    }

    @Test
    @DisplayName("reportFraud without confirmed=true opens no case")
    void reportFraudRequiresConfirmation() {
        String result = tools.reportFraud("TXN-5003", "never made this purchase", null, ctxFor(demoUser));
        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
    }

    @Test
    @DisplayName("reportFraud opens a fraud-flagged case once confirmed")
    void reportFraudOpensFlaggedCase() {
        String result = tools.reportFraud("TXN-5003", "never made this purchase", true, ctxFor(demoUser));
        assertThat(result).contains("Fraud report filed").contains("CASE-");
    }
}
