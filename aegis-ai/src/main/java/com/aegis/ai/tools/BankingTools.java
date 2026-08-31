package com.aegis.ai.tools;

import com.aegis.ai.domain.BankingService;
import com.aegis.ai.security.AccessDeniedException;
import com.aegis.ai.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class BankingTools {

    private static final Logger log = LoggerFactory.getLogger(BankingTools.class);
    public static final String PRINCIPAL_KEY = "principal";
    
    public static final String DYNAMIC_ACCESS_KEY = "dynamicAccess";
    
    public static final String STATUS_KEY = "statusSink";

    @SuppressWarnings("unchecked")
    public static void status(ToolContext ctx, String message) {
        if (ctx.getContext().get(STATUS_KEY) instanceof Consumer<?> c) {
            ((Consumer<String>) c).accept(message);
        }
    }

    private final BankingService banking;
    private final com.aegis.ai.admin.AuditTrail audit;

    public BankingTools(BankingService banking, com.aegis.ai.admin.AuditTrail audit) {
        this.banking = banking;
        this.audit = audit;
    }

    private Principal principal(ToolContext ctx) {

        if (ctx.getContext().get(DYNAMIC_ACCESS_KEY) instanceof AtomicBoolean b) {
            b.set(true);
        }
        Object p = ctx.getContext().get(PRINCIPAL_KEY);
        if (!(p instanceof Principal principal)) {
            throw new AccessDeniedException("No authenticated principal in tool context");
        }
        return principal;
    }

    private void require(Principal p, String permission) {
        if (!p.can(permission)) {
            log.warn("tool.authz.denied user={} missingPermission={}", p.userId(), permission);
            audit.toolCalled("denied:" + permission, p.tenantId());
            throw new AccessDeniedException("User " + p.userId() + " lacks permission: " + permission);
        }
    }

    @Tool(description = "Look up an account's current balance by account id")
    public String lookupBalance(@ToolParam(description = "account id, e.g. ACC-1001") String accountId,
                                ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("lookupBalance", p.tenantId());
        status(ctx, "Looking up balance for " + accountId + "…");
        var acct = banking.getAccount(accountId);
        if (acct == null) return "No account found: " + accountId;
        if (!acct.tenantId().equals(p.tenantId())) {
            throw new AccessDeniedException("Cross-tenant account access denied");
        }
        log.info("tool.lookupBalance user={} account={}", p.userId(), accountId);
        return "Account " + accountId + " balance: $" + acct.balance();
    }

    @Tool(description = "List recent transactions for an account id")
    public String searchTransactions(@ToolParam(description = "account id") String accountId,
                                     ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("searchTransactions", p.tenantId());
        status(ctx, "Fetching transactions for " + accountId + "…");
        var list = banking.getTransactions(accountId);
        if (list.isEmpty()) return "No transactions for " + accountId;
        StringBuilder sb = new StringBuilder("Transactions for " + accountId + ":\n");
        list.forEach(t -> sb.append("- ").append(t.txnId()).append(" ").append(t.date())
                .append(" $").append(t.amount()).append(" ").append(t.merchant()).append("\n"));
        log.info("tool.searchTransactions user={} account={} count={}", p.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool(description = "Create a dispute case for a transaction. The transaction must "
            + "exist and belong to the current user's tenant; if it doesn't, this fails.")
    public String createDisputeCase(@ToolParam(description = "transaction id, e.g. TXN-5001") String transactionId,
                                    @ToolParam(description = "reason for the dispute") String reason,
                                    ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cases:create");

        audit.toolCalled("createDisputeCase", p.tenantId());
        status(ctx, "Opening a dispute case for " + transactionId + "…");

        var txn = banking.findTransaction(transactionId);
        if (txn == null) {
            log.warn("tool.createDisputeCase.reject user={} reason=txn_not_found txn={}", p.userId(), transactionId);
            return "No transaction found with id " + transactionId + ". Please check the id.";
        }
        var acct = banking.getAccount(txn.accountId());
        if (acct == null || !acct.tenantId().equals(p.tenantId())) {
            log.warn("tool.createDisputeCase.reject user={} reason=cross_tenant txn={}", p.userId(), transactionId);
            throw new AccessDeniedException("That transaction is not on an account you can access.");
        }

        var c = banking.createCase(txn.accountId(), transactionId, reason);
        log.info("tool.createDisputeCase user={} case={} txn={}", p.userId(), c.caseId(), transactionId);
        return "Created dispute case " + c.caseId() + " for transaction " + transactionId
                + " on account " + txn.accountId() + " (status " + c.status() + ")";
    }

    @Tool(description = "List the cards linked to an account id (type, last 4 digits, status)")
    public String listCards(@ToolParam(description = "account id") String accountId, ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("listCards", p.tenantId());
        status(ctx, "Fetching cards for " + accountId + "…");
        var acct = banking.getAccount(accountId);
        if (acct == null) return "No account found: " + accountId;
        if (!acct.tenantId().equals(p.tenantId())) {
            throw new AccessDeniedException("Cross-tenant account access denied");
        }
        var list = banking.getCards(accountId);
        if (list.isEmpty()) return "No cards on " + accountId;
        StringBuilder sb = new StringBuilder("Cards on " + accountId + ":\n");
        list.forEach(c -> sb.append("- ").append(c.cardId()).append(" ").append(c.type())
                .append(" ****").append(c.last4()).append(" (").append(c.status()).append(")\n"));
        log.info("tool.listCards user={} account={} count={}", p.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool(description = "Freeze (block) a card immediately, e.g. reported lost/stolen or fraud "
            + "suspected. Protective and reversible by staff.")
    public String freezeCard(@ToolParam(description = "card id, e.g. CRD-7001") String cardId,
                             @ToolParam(description = "reason for the freeze") String reason,
                             ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        audit.toolCalled("freezeCard", p.tenantId());
        status(ctx, "Freezing card " + cardId + "…");
        var card = banking.findCard(cardId);
        if (card == null) return "No card found with id " + cardId + ".";
        var acct = banking.getAccount(card.accountId());
        if (acct == null || !acct.tenantId().equals(p.tenantId())) {
            throw new AccessDeniedException("That card is not on an account you can access.");
        }
        if ("FROZEN".equals(card.status())) return "Card " + cardId + " is already frozen.";
        var frozen = banking.freezeCard(cardId);
        log.info("tool.freezeCard user={} card={} reason={}", p.userId(), cardId, reason);
        return "Card " + cardId + " (****" + frozen.last4() + ") is now FROZEN. Reason: " + reason
                + ". A replacement can be ordered if needed.";
    }

    @Tool(description = "Order a replacement card for a lost/stolen/damaged card. Creates a "
            + "human-approval request; does not charge the customer directly.")
    public String requestCardReplacement(@ToolParam(description = "card id to replace") String cardId,
                                         @ToolParam(description = "reason for replacement") String reason,
                                         ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        audit.toolCalled("requestCardReplacement", p.tenantId());
        status(ctx, "Ordering a replacement for " + cardId + "…");
        var card = banking.findCard(cardId);
        if (card == null) return "No card found with id " + cardId + ".";
        var acct = banking.getAccount(card.accountId());
        if (acct == null || !acct.tenantId().equals(p.tenantId())) {
            throw new AccessDeniedException("That card is not on an account you can access.");
        }
        
        var approval = banking.requestApproval("CARD-REPLACEMENT:" + cardId,
                new BigDecimal("5.00"), p.userId());
        log.info("tool.requestCardReplacement user={} card={} approval={}", p.userId(), cardId,
                approval.approvalId());
        return "Replacement for card " + cardId + " requested (approval id " + approval.approvalId()
                + ", status " + approval.status() + "). Standard delivery 5-7 business days; "
                + "the $5 fee can be waived by the approver per policy. Reason: " + reason;
    }

    @Tool(description = "Look up the status of a dispute case by case id")
    public String getCaseStatus(@ToolParam(description = "case id, e.g. CASE-1001") String caseId,
                                ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("getCaseStatus", p.tenantId());
        status(ctx, "Checking status of " + caseId + "…");
        var c = banking.getCase(caseId);
        if (c == null) return "No dispute case found with id " + caseId + ".";
        var acct = banking.getAccount(c.accountId());
        if (acct == null || !acct.tenantId().equals(p.tenantId())) {
            throw new AccessDeniedException("That case is not on an account you can access.");
        }
        log.info("tool.getCaseStatus user={} case={}", p.userId(), caseId);
        return "Case " + caseId + ": status " + c.status() + ", transaction " + c.transactionId()
                + " on account " + c.accountId() + ", reason: " + c.reason();
    }

    @Tool(description = "Issue a provisional credit to a customer for a dispute case. "
            + "This does not move money directly; it requests human approval.")
    public String issueProvisionalCredit(@ToolParam(description = "case id") String caseId,
                                         @ToolParam(description = "credit amount in dollars") String amount,
                                         ToolContext ctx) {
        var p = principal(ctx);
        require(p, "credit:request");
        audit.toolCalled("issueProvisionalCredit", p.tenantId());
        status(ctx, "Requesting provisional credit approval…");
        
        var approval = banking.requestApproval(caseId, new BigDecimal(amount), p.userId());
        log.info("tool.issueProvisionalCredit user={} case={} amount={} -> {} (approval {})",
                p.userId(), caseId, amount, approval.status(), approval.approvalId());
        return "Provisional credit of $" + amount + " for case " + caseId
                + " is " + approval.status() + " (approval id " + approval.approvalId()
                + "). No funds have moved.";
    }
}
