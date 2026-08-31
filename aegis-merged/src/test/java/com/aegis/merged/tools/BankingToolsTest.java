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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import java.math.BigDecimal;

class BankingToolsTest {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9]+)");

    private BankingTools tools;
    private Principal demoUser;

    @BeforeEach
    void setUp() {
        BankingService banking = new BankingService();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        tools = new BankingTools(banking, new AuditTrail(jdbc), new com.aegis.merged.kyc.MockKycProvider(),
                new ConfirmationGuard());

        demoUser = new Principal("demo-user", "achu-bank", JwtService.permissionsFor("customer"));
    }

    private ToolContext ctxFor(Principal p) {
        return new ToolContext(Map.of(BankingTools.PRINCIPAL_KEY, p));
    }

    private static String extractToken(String confirmationRequiredMessage) {
        Matcher m = TOKEN_PATTERN.matcher(confirmationRequiredMessage);
        if (!m.find()) {
            throw new IllegalStateException("no confirmationToken found in: " + confirmationRequiredMessage);
        }
        return m.group(1);
    }

    @Test
    @DisplayName("freezeCard without a confirmationToken does NOT freeze the card")
    void freezeRequiresConfirmation() {
        String result = tools.freezeCard("CRD-7001", "lost", null, ctxFor(demoUser));

        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
        
        String status = tools.listCards("ACC-1001", ctxFor(demoUser));
        assertThat(status).contains("CRD-7001").contains("ACTIVE");
    }

    @Test
    @DisplayName("freezeCard with a real confirmationToken actually freezes the card")
    void freezeExecutesOnceConfirmed() {
        String pending = tools.freezeCard("CRD-7001", "lost", null, ctxFor(demoUser));
        String result = tools.freezeCard("CRD-7001", "lost", extractToken(pending), ctxFor(demoUser));

        assertThat(result).contains("FROZEN");
        String status = tools.listCards("ACC-1001", ctxFor(demoUser));
        assertThat(status).contains("CRD-7001").contains("FROZEN");
    }

    @Test
    @DisplayName("A confirmationToken cannot be replayed to freeze the card a second time")
    void freezeTokenIsSingleUse() {
        String pending = tools.freezeCard("CRD-7001", "lost", null, ctxFor(demoUser));
        String token = extractToken(pending);
        tools.freezeCard("CRD-7001", "lost", token, ctxFor(demoUser));

        String unfreezePending = tools.unfreezeCard("CRD-7001", null, ctxFor(demoUser));
        tools.unfreezeCard("CRD-7001", extractToken(unfreezePending), ctxFor(demoUser));

        String replay = tools.freezeCard("CRD-7001", "lost", token, ctxFor(demoUser));
        assertThat(replay).startsWith("CONFIRMATION_REQUIRED");
        assertThat(tools.listCards("ACC-1001", ctxFor(demoUser))).contains("CRD-7001").contains("ACTIVE");
    }

    @Test
    @DisplayName("A token minted for one card cannot confirm freezing a different card")
    void freezeTokenIsBoundToItsExactArguments() {
        String pendingFor7001 = tools.freezeCard("CRD-7001", "lost", null, ctxFor(demoUser));
        String tokenFor7001 = extractToken(pendingFor7001);

        String result = tools.freezeCard("CRD-7002", "lost", tokenFor7001, ctxFor(demoUser));
        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
        assertThat(tools.listCards("ACC-1001", ctxFor(demoUser)))
                .doesNotContain("CRD-7002 CREDIT ****8830 (FROZEN)");
    }

    @Test
    @DisplayName("A card never mentioned in the conversation is untouched by freezing a different one")
    void onlyTheConfirmedCardIsFrozen() {
        String pending = tools.freezeCard("CRD-7001", "lost", null, ctxFor(demoUser));
        tools.freezeCard("CRD-7001", "lost", extractToken(pending), ctxFor(demoUser));

        String otherStatus = tools.listCards("ACC-1001", ctxFor(demoUser));
        assertThat(otherStatus).contains("CRD-7002").doesNotContain("CRD-7002 CREDIT ****8830 (FROZEN)");
    }

    @Test
    @DisplayName("requestCardReplacement without a confirmationToken creates no approval")
    void replacementRequiresConfirmation() {
        String result = tools.requestCardReplacement("CRD-7001", "lost", null, ctxFor(demoUser));
        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
    }

    @Test
    @DisplayName("transferBetweenOwnAccounts without a confirmationToken moves no money")
    void transferRequiresConfirmation() {
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "100", null, ctxFor(demoUser));
        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("transferBetweenOwnAccounts posts a real double-entry movement that nets to zero")
    void transferMovesMoneyBothWays() {
        String pending = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "100", null, ctxFor(demoUser));
        tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "100", extractToken(pending), ctxFor(demoUser));

        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2400.00");
        assertThat(tools.lookupBalance("ACC-1002", ctxFor(demoUser))).contains("15850.25");
    }

    @Test
    @DisplayName("transferBetweenOwnAccounts rejects a transfer larger than the source balance")
    void transferRejectsInsufficientFunds() {

        String pending = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "10000", null, ctxFor(demoUser));
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "10000", extractToken(pending), ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("insufficient");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("Negative or non-positive transfer amounts are rejected, not reversed")
    void transferRejectsNonPositiveAmount() {
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "-100", null, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("positive");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("Transfers above the per-transfer cap are rejected")
    void transferRejectsAmountAboveCap() {
        String result = tools.transferBetweenOwnAccounts("ACC-1001", "ACC-1002", "999999", null, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("limit");
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("A customer cannot transfer into an account they don't own")
    void transferRejectsForeignDestination() {
        assertThrows(com.aegis.merged.security.AccessDeniedException.class,
                () -> tools.transferBetweenOwnAccounts("ACC-1001", "ACC-9001", "10", null, ctxFor(demoUser)));
        assertThat(tools.lookupBalance("ACC-1001", ctxFor(demoUser))).contains("2500.00");
    }

    @Test
    @DisplayName("unfreezeCard reverses freezeCard")
    void unfreezeReversesFreeze() {
        String freezePending = tools.freezeCard("CRD-7001", "lost", null, ctxFor(demoUser));
        tools.freezeCard("CRD-7001", "lost", extractToken(freezePending), ctxFor(demoUser));
        String unfreezePending = tools.unfreezeCard("CRD-7001", null, ctxFor(demoUser));
        tools.unfreezeCard("CRD-7001", extractToken(unfreezePending), ctxFor(demoUser));
        assertThat(tools.listCards("ACC-1001", ctxFor(demoUser))).contains("CRD-7001").contains("ACTIVE");
    }

    @Test
    @DisplayName("reportFraud without a confirmationToken opens no case")
    void reportFraudRequiresConfirmation() {
        String result = tools.reportFraud("TXN-5003", "never made this purchase", null, ctxFor(demoUser));
        assertThat(result).startsWith("CONFIRMATION_REQUIRED");
    }

    @Test
    @DisplayName("reportFraud opens a fraud-flagged case once confirmed")
    void reportFraudOpensFlaggedCase() {
        String pending = tools.reportFraud("TXN-5003", "never made this purchase", null, ctxFor(demoUser));
        String result = tools.reportFraud("TXN-5003", "never made this purchase", extractToken(pending), ctxFor(demoUser));
        assertThat(result).contains("Fraud report filed").contains("CASE-");
    }

    @Test
    @DisplayName("issueProvisionalCredit rejects an amount wildly above the disputed transaction")
    void provisionalCreditRejectsAmountAboveDisputedTxn() {
        
        String createPending = tools.createDisputeCase("TXN-5001", "double charge", null, ctxFor(demoUser));
        String created = tools.createDisputeCase("TXN-5001", "double charge", extractToken(createPending), ctxFor(demoUser));
        String caseId = created.split(" ")[3];

        String result = tools.issueProvisionalCredit(caseId, "1000000", null, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("exceeds");
    }

    @Test
    @DisplayName("issueProvisionalCredit rejects a non-positive amount")
    void provisionalCreditRejectsNonPositiveAmount() {
        String createPending = tools.createDisputeCase("TXN-5001", "double charge", null, ctxFor(demoUser));
        String created = tools.createDisputeCase("TXN-5001", "double charge", extractToken(createPending), ctxFor(demoUser));
        String caseId = created.split(" ")[3];
        String result = tools.issueProvisionalCredit(caseId, "-5", null, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("positive");
    }

    @Test
    @DisplayName("setCardSpendingLimit rejects a negative limit")
    void spendingLimitRejectsNegative() {
        String result = tools.setCardSpendingLimit("CRD-7001", "-100", null, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("positive");
    }

    @Test
    @DisplayName("toggleMerchantCategoryBlock rejects an unknown category")
    void merchantCategoryRejectsUnknownValue() {
        String result = tools.toggleMerchantCategoryBlock("CRD-7001", "NOT_A_REAL_CATEGORY", true, null, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("unknown");
    }

    @Test
    @DisplayName("setCardSpendingLimit requires confirmation before taking effect")
    void spendingLimitRequiresConfirmation() {
        String pending = tools.setCardSpendingLimit("CRD-7001", "500", null, ctxFor(demoUser));
        assertThat(pending).startsWith("CONFIRMATION_REQUIRED");

        String confirmed = tools.setCardSpendingLimit("CRD-7001", "500", extractToken(pending), ctxFor(demoUser));
        assertThat(confirmed).contains("$500");
    }

    @Test
    @DisplayName("toggleMerchantCategoryBlock requires confirmation before taking effect")
    void merchantCategoryBlockRequiresConfirmation() {
        String pending = tools.toggleMerchantCategoryBlock("CRD-7001", "GAMBLING", true, null, ctxFor(demoUser));
        assertThat(pending).startsWith("CONFIRMATION_REQUIRED");

        String confirmed = tools.toggleMerchantCategoryBlock(
                "CRD-7001", "GAMBLING", true, extractToken(pending), ctxFor(demoUser));
        assertThat(confirmed).containsIgnoringCase("now blocks");
    }

    @Test
    @DisplayName("updateContactInfo rejects a malformed email")
    void contactInfoRejectsBadEmail() {
        String result = tools.updateContactInfo("not-an-email", null, null, ctxFor(demoUser));
        assertThat(result).containsIgnoringCase("email");
    }

    @Test
    @DisplayName("setTravelNotice rejects a date far in the past or the distant future")
    void travelNoticeRejectsUnreasonableDates() {
        assertThat(tools.setTravelNotice("2001-01-01", "Nowhere", ctxFor(demoUser)))
                .containsIgnoringCase("past");
        assertThat(tools.setTravelNotice("9999-12-31", "Nowhere", ctxFor(demoUser)))
                .containsIgnoringCase("days out");
    }

    @Test
    @DisplayName("getSpendingSummary sums only debits into category totals — a refund/credit in "
            + "the same category must not inflate it")
    void spendingSummaryDoesNotMixCreditsIntoCategoryTotals() {
        BankingService banking = new BankingService();
        banking.transfer("ACC-1001", "ACC-1002", new BigDecimal("100.00"), "test-out");
        banking.transfer("ACC-1002", "ACC-1001", new BigDecimal("100.00"), "test-in");

        var freshTools = new BankingTools(banking, new AuditTrail(mock(JdbcTemplate.class)),
                new com.aegis.merged.kyc.MockKycProvider(), new ConfirmationGuard());

        String result = freshTools.getSpendingSummary("ACC-1001", ctxFor(demoUser));

        assertThat(result).contains("Transfers: $100.00");
        assertThat(result).doesNotContain("Transfers: $200.00");
    }
}
