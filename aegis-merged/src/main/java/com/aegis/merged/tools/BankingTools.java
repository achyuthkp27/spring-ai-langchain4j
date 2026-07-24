package com.aegis.merged.tools;

import com.aegis.merged.domain.BankingService;
import com.aegis.merged.security.AccessDeniedException;
import com.aegis.merged.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Tools the customer assistant can call. Security invariants, all enforced in
 * code (not by prompt):
 *   1. The Principal flows via ToolContext — the model never sees or sets it.
 *   2. Authorization AND ownership are checked INSIDE every tool: a customer can
 *      only touch accounts they own (tenant AND ownerUserId — backported from the
 *      LangChain4j build, which tightened the original tenant-only check).
 *   3. Money movement only ever creates a PENDING_HUMAN_APPROVAL request
 *      (OWASP LLM06: excessive agency is structurally impossible).
 */
@Component
public class BankingTools {

    private static final Logger log = LoggerFactory.getLogger(BankingTools.class);
    public static final String PRINCIPAL_KEY = "principal";
    /** A caller can pass an AtomicBoolean here; it's flipped true whenever any
        account/action tool runs, so those (dynamic) answers are never cached. */
    public static final String DYNAMIC_ACCESS_KEY = "dynamicAccess";
    /** Optional Consumer&lt;String&gt;: tools report human-readable progress that the
        streaming endpoint forwards as SSE status events. */
    public static final String STATUS_KEY = "statusSink";

    @SuppressWarnings("unchecked")
    public static void status(ToolContext ctx, String message) {
        if (ctx.getContext().get(STATUS_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<String>) c).accept(message);
        }
    }

    private final BankingService banking;
    private final com.aegis.merged.admin.AuditTrail audit;

    public BankingTools(BankingService banking, com.aegis.merged.admin.AuditTrail audit) {
        this.banking = banking;
        this.audit = audit;
    }

    private Principal principal(ToolContext ctx) {
        // Any banking tool touching account/action data marks the request dynamic,
        // so the assistant will not cache its answer (balances change, are per-user).
        if (ctx.getContext().get(DYNAMIC_ACCESS_KEY) instanceof java.util.concurrent.atomic.AtomicBoolean b) {
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
            throw new AccessDeniedException("You don't have permission for that action.");
        }
    }

    /** Account must exist, be in the caller's tenant AND be owned by the caller. */
    private BankingService.Account ownedAccount(Principal p, String accountId) {
        var acct = banking.getAccount(accountId);
        if (acct == null) return null;
        if (!acct.tenantId().equals(p.tenantId()) || !acct.ownerUserId().equals(p.userId())) {
            throw new AccessDeniedException("That account isn't one of yours.");
        }
        return acct;
    }

    @Tool(description = "List the current customer's own accounts (id, type, balance). "
            + "Use this first when they ask about 'my account' without naming an id.")
    public String listMyAccounts(ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("listMyAccounts", p.tenantId());
        status(ctx, "Fetching your accounts…");
        var list = banking.accountsOf(p.tenantId(), p.userId());
        if (list.isEmpty()) return "You have no accounts on file.";
        StringBuilder sb = new StringBuilder("Your accounts:\n");
        list.forEach(a -> sb.append("- ").append(a.accountId()).append(" ").append(a.type())
                .append(" balance $").append(a.balance()).append("\n"));
        log.info("tool.listMyAccounts user={} count={}", p.userId(), list.size());
        return sb.toString();
    }

    @Tool(description = "Look up the current balance of one of the customer's own accounts by account id")
    public String lookupBalance(@ToolParam(description = "account id, e.g. ACC-1001") String accountId,
                                ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("lookupBalance", p.tenantId());
        status(ctx, "Looking up balance for " + accountId + "…");
        var acct = ownedAccount(p, accountId);
        if (acct == null) return "No account found: " + accountId;
        log.info("tool.lookupBalance user={} account={}", p.userId(), accountId);
        return "Account " + accountId + " (" + acct.type() + ") balance: $" + acct.balance();
    }

    @Tool(description = "List recent transactions for one of the customer's own accounts")
    public String searchTransactions(@ToolParam(description = "account id") String accountId,
                                     ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("searchTransactions", p.tenantId());
        status(ctx, "Fetching transactions for " + accountId + "…");
        var acct = ownedAccount(p, accountId);
        if (acct == null) return "No account found: " + accountId;
        var list = banking.getTransactions(accountId);
        if (list.isEmpty()) return "No transactions for " + accountId;
        StringBuilder sb = new StringBuilder("Transactions for " + accountId + ":\n");
        list.forEach(t -> sb.append("- ").append(t.txnId()).append(" ").append(t.date())
                .append(" $").append(t.amount()).append(" ").append(t.merchant()).append("\n"));
        log.info("tool.searchTransactions user={} account={} count={}", p.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool(description = "Open a dispute case for one of the customer's own transactions. The "
            + "transaction must exist on one of their accounts; if it doesn't, this fails.")
    public String createDisputeCase(@ToolParam(description = "transaction id, e.g. TXN-5001") String transactionId,
                                    @ToolParam(description = "reason for the dispute") String reason,
                                    ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cases:create");
        audit.toolCalled("createDisputeCase", p.tenantId());
        status(ctx, "Opening a dispute case for " + transactionId + "…");

        // Validate the transaction exists AND is on an account the caller owns BEFORE
        // creating a case — never open a dispute against a foreign transaction id.
        var txn = banking.findTransaction(transactionId);
        if (txn == null) {
            log.warn("tool.createDisputeCase.reject user={} reason=txn_not_found txn={}", p.userId(), transactionId);
            return "No transaction found with id " + transactionId + ". Please check the id.";
        }
        if (ownedAccount(p, txn.accountId()) == null) {
            throw new AccessDeniedException("That transaction is not on one of your accounts.");
        }

        var c = banking.createCase(txn.accountId(), transactionId, reason);
        log.info("tool.createDisputeCase user={} case={} txn={}", p.userId(), c.caseId(), transactionId);
        return "Created dispute case " + c.caseId() + " for transaction " + transactionId
                + " on account " + txn.accountId() + " (status " + c.status() + ")";
    }

    @Tool(description = "List the cards on one of the customer's own accounts (type, last 4 digits, status)")
    public String listCards(@ToolParam(description = "account id") String accountId, ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("listCards", p.tenantId());
        status(ctx, "Fetching cards for " + accountId + "…");
        var acct = ownedAccount(p, accountId);
        if (acct == null) return "No account found: " + accountId;
        var list = banking.getCards(accountId);
        if (list.isEmpty()) return "No cards on " + accountId;
        StringBuilder sb = new StringBuilder("Cards on " + accountId + ":\n");
        list.forEach(c -> sb.append("- ").append(c.cardId()).append(" ").append(c.type())
                .append(" ****").append(c.last4()).append(" (").append(c.status()).append(")\n"));
        log.info("tool.listCards user={} account={} count={}", p.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool(description = "Freeze (block) one of the customer's own cards immediately, e.g. reported "
            + "lost/stolen or fraud suspected. Protective and reversible.")
    public String freezeCard(@ToolParam(description = "card id, e.g. CRD-7001") String cardId,
                             @ToolParam(description = "reason for the freeze") String reason,
                             ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        audit.toolCalled("freezeCard", p.tenantId());
        status(ctx, "Freezing card " + cardId + "…");
        var card = banking.findCard(cardId);
        if (card == null) return "No card found with id " + cardId + ".";
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        if ("FROZEN".equals(card.status())) return "Card " + cardId + " is already frozen.";
        var frozen = banking.freezeCard(cardId);
        log.info("tool.freezeCard user={} card={} reason={}", p.userId(), cardId, reason);
        return "Card " + cardId + " (****" + frozen.last4() + ") is now FROZEN. Reason: " + reason
                + ". A replacement can be ordered if needed.";
    }

    @Tool(description = "Order a replacement for one of the customer's own cards (lost/stolen/damaged). "
            + "Creates a human-approval request; does not charge the customer directly.")
    public String requestCardReplacement(@ToolParam(description = "card id to replace") String cardId,
                                         @ToolParam(description = "reason for replacement") String reason,
                                         ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        audit.toolCalled("requestCardReplacement", p.tenantId());
        status(ctx, "Ordering a replacement for " + cardId + "…");
        var card = banking.findCard(cardId);
        if (card == null) return "No card found with id " + cardId + ".";
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        // Standard replacement fee per card-services policy; waivable by the approver.
        var approval = banking.requestApproval("CARD-REPLACEMENT:" + cardId,
                new BigDecimal("5.00"), p.userId());
        log.info("tool.requestCardReplacement user={} card={} approval={}", p.userId(), cardId,
                approval.approvalId());
        return "Replacement for card " + cardId + " requested (approval id " + approval.approvalId()
                + ", status " + approval.status() + "). Standard delivery 5-7 business days; "
                + "the $5 fee can be waived per policy. Reason: " + reason;
    }

    @Tool(description = "Look up the status of one of the customer's own dispute cases by case id")
    public String getCaseStatus(@ToolParam(description = "case id, e.g. CASE-1001") String caseId,
                                ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("getCaseStatus", p.tenantId());
        status(ctx, "Checking status of " + caseId + "…");
        var c = banking.getCase(caseId);
        if (c == null) return "No dispute case found with id " + caseId + ".";
        if (ownedAccount(p, c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }
        log.info("tool.getCaseStatus user={} case={}", p.userId(), caseId);
        return "Case " + caseId + ": status " + c.status() + ", transaction " + c.transactionId()
                + " on account " + c.accountId() + ", reason: " + c.reason();
    }

    @Tool(description = "Request a provisional credit for one of the customer's own dispute cases. "
            + "This does not move money; it creates a request for human approval by bank staff.")
    public String issueProvisionalCredit(@ToolParam(description = "case id") String caseId,
                                         @ToolParam(description = "credit amount in dollars") String amount,
                                         ToolContext ctx) {
        var p = principal(ctx);
        require(p, "credit:request");
        audit.toolCalled("issueProvisionalCredit", p.tenantId());
        status(ctx, "Requesting provisional credit approval…");
        // Ownership check backported from the LC4j build — the original allowed a
        // credit request against any case id.
        var c = banking.getCase(caseId);
        if (c == null) return "No dispute case found with id " + caseId + ".";
        if (ownedAccount(p, c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }
        // Human-in-the-loop: the model can REQUEST money movement, never EXECUTE it.
        var approval = banking.requestApproval(caseId, new BigDecimal(amount), p.userId());
        log.info("tool.issueProvisionalCredit user={} case={} amount={} -> {} (approval {})",
                p.userId(), caseId, amount, approval.status(), approval.approvalId());
        return "Provisional credit of $" + amount + " for case " + caseId
                + " is " + approval.status() + " (approval id " + approval.approvalId()
                + "). No funds have moved; bank staff will review it.";
    }
}
