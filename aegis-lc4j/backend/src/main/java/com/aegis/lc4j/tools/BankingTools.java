package com.aegis.lc4j.tools;

import com.aegis.lc4j.admin.AuditTrail;
import com.aegis.lc4j.domain.BankingService;
import com.aegis.lc4j.security.AccessDeniedException;
import com.aegis.lc4j.security.Principal;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class BankingTools {

    private static final Logger log = LoggerFactory.getLogger(BankingTools.class);

    private final BankingService banking;
    private final AuditTrail audit;
    private final Principal principal;
    
    private final AtomicBoolean dynamicAccess;
    
    private final Consumer<String> statusSink;

    public BankingTools(BankingService banking, AuditTrail audit, Principal principal,
                        AtomicBoolean dynamicAccess, Consumer<String> statusSink) {
        this.banking = banking;
        this.audit = audit;
        this.principal = principal;
        this.dynamicAccess = dynamicAccess;
        this.statusSink = statusSink;
    }

    private void dynamic() {
        dynamicAccess.set(true);
    }

    private void status(String message) {
        statusSink.accept(message);
    }

    private void require(String permission) {
        if (!principal.can(permission)) {
            log.warn("tool.authz.denied user={} missingPermission={}", principal.userId(), permission);
            audit.toolCalled("denied:" + permission, principal.tenantId());
            throw new AccessDeniedException("You don't have permission for that action.");
        }
    }

    private BankingService.Account ownedAccount(String accountId) {
        var acct = banking.getAccount(accountId);
        if (acct == null) return null;
        if (!acct.tenantId().equals(principal.tenantId())
                || !acct.ownerUserId().equals(principal.userId())) {
            throw new AccessDeniedException("That account isn't one of yours.");
        }
        return acct;
    }

    @Tool("List the current customer's own accounts (id, type, balance). "
            + "Use this first when they ask about 'my account' without naming an id.")
    public String listMyAccounts() {
        dynamic();
        require("account:read");
        audit.toolCalled("listMyAccounts", principal.tenantId());
        status("Fetching your accounts…");
        var list = banking.accountsOf(principal.tenantId(), principal.userId());
        if (list.isEmpty()) return "You have no accounts on file.";
        StringBuilder sb = new StringBuilder("Your accounts:\n");
        list.forEach(a -> sb.append("- ").append(a.accountId()).append(" ").append(a.type())
                .append(" balance $").append(a.balance()).append("\n"));
        log.info("tool.listMyAccounts user={} count={}", principal.userId(), list.size());
        return sb.toString();
    }

    @Tool("Look up the current balance of one of the customer's own accounts by account id")
    public String lookupBalance(@P("account id, e.g. ACC-1001") String accountId) {
        dynamic();
        require("account:read");
        audit.toolCalled("lookupBalance", principal.tenantId());
        status("Looking up balance for " + accountId + "…");
        var acct = ownedAccount(accountId);
        if (acct == null) return "No account found: " + accountId;
        log.info("tool.lookupBalance user={} account={}", principal.userId(), accountId);
        return "Account " + accountId + " (" + acct.type() + ") balance: $" + acct.balance();
    }

    @Tool("List recent transactions for one of the customer's own accounts")
    public String searchTransactions(@P("account id") String accountId) {
        dynamic();
        require("account:read");
        audit.toolCalled("searchTransactions", principal.tenantId());
        status("Fetching transactions for " + accountId + "…");
        var acct = ownedAccount(accountId);
        if (acct == null) return "No account found: " + accountId;
        var list = banking.getTransactions(accountId);
        if (list.isEmpty()) return "No transactions for " + accountId;
        StringBuilder sb = new StringBuilder("Transactions for " + accountId + ":\n");
        list.forEach(t -> sb.append("- ").append(t.txnId()).append(" ").append(t.date())
                .append(" $").append(t.amount()).append(" ").append(t.merchant()).append("\n"));
        log.info("tool.searchTransactions user={} account={} count={}",
                principal.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool("Open a dispute case for one of the customer's own transactions. The transaction "
            + "must exist on one of their accounts; if it doesn't, this fails.")
    public String createDisputeCase(@P("transaction id, e.g. TXN-5001") String transactionId,
                                    @P("reason for the dispute") String reason) {
        dynamic();
        require("cases:create");
        audit.toolCalled("createDisputeCase", principal.tenantId());
        status("Opening a dispute case for " + transactionId + "…");

        var txn = banking.findTransaction(transactionId);
        if (txn == null) {
            log.warn("tool.createDisputeCase.reject user={} reason=txn_not_found txn={}",
                    principal.userId(), transactionId);
            return "No transaction found with id " + transactionId + ". Please check the id.";
        }
        if (ownedAccount(txn.accountId()) == null) {
            throw new AccessDeniedException("That transaction is not on one of your accounts.");
        }
        var c = banking.createCase(txn.accountId(), transactionId, reason);
        log.info("tool.createDisputeCase user={} case={} txn={}",
                principal.userId(), c.caseId(), transactionId);
        return "Created dispute case " + c.caseId() + " for transaction " + transactionId
                + " on account " + txn.accountId() + " (status " + c.status() + ")";
    }

    @Tool("List the cards on one of the customer's own accounts (type, last 4 digits, status)")
    public String listCards(@P("account id") String accountId) {
        dynamic();
        require("account:read");
        audit.toolCalled("listCards", principal.tenantId());
        status("Fetching cards for " + accountId + "…");
        var acct = ownedAccount(accountId);
        if (acct == null) return "No account found: " + accountId;
        var list = banking.getCards(accountId);
        if (list.isEmpty()) return "No cards on " + accountId;
        StringBuilder sb = new StringBuilder("Cards on " + accountId + ":\n");
        list.forEach(c -> sb.append("- ").append(c.cardId()).append(" ").append(c.type())
                .append(" ****").append(c.last4()).append(" (").append(c.status()).append(")\n"));
        log.info("tool.listCards user={} account={} count={}",
                principal.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool("Freeze (block) one of the customer's own cards immediately, e.g. reported "
            + "lost/stolen or fraud suspected. Protective and reversible.")
    public String freezeCard(@P("card id, e.g. CRD-7001") String cardId,
                             @P("reason for the freeze") String reason) {
        dynamic();
        require("cards:manage");
        audit.toolCalled("freezeCard", principal.tenantId());
        status("Freezing card " + cardId + "…");
        var card = banking.findCard(cardId);
        if (card == null) return "No card found with id " + cardId + ".";
        if (ownedAccount(card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        if ("FROZEN".equals(card.status())) return "Card " + cardId + " is already frozen.";
        var frozen = banking.freezeCard(cardId);
        log.info("tool.freezeCard user={} card={} reason={}", principal.userId(), cardId, reason);
        return "Card " + cardId + " (****" + frozen.last4() + ") is now FROZEN. Reason: " + reason
                + ". A replacement can be ordered if needed.";
    }

    @Tool("Order a replacement for one of the customer's own cards (lost/stolen/damaged). "
            + "Creates a human-approval request; does not charge the customer directly.")
    public String requestCardReplacement(@P("card id to replace") String cardId,
                                         @P("reason for replacement") String reason) {
        dynamic();
        require("cards:manage");
        audit.toolCalled("requestCardReplacement", principal.tenantId());
        status("Ordering a replacement for " + cardId + "…");
        var card = banking.findCard(cardId);
        if (card == null) return "No card found with id " + cardId + ".";
        if (ownedAccount(card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        
        var approval = banking.requestApproval("CARD-REPLACEMENT:" + cardId,
                new BigDecimal("5.00"), principal.userId());
        log.info("tool.requestCardReplacement user={} card={} approval={}",
                principal.userId(), cardId, approval.approvalId());
        return "Replacement for card " + cardId + " requested (approval id " + approval.approvalId()
                + ", status " + approval.status() + "). Standard delivery 5-7 business days; "
                + "the $5 fee can be waived per policy. Reason: " + reason;
    }

    @Tool("Look up the status of one of the customer's own dispute cases by case id")
    public String getCaseStatus(@P("case id, e.g. CASE-1001") String caseId) {
        dynamic();
        require("account:read");
        audit.toolCalled("getCaseStatus", principal.tenantId());
        status("Checking status of " + caseId + "…");
        var c = banking.getCase(caseId);
        if (c == null) return "No dispute case found with id " + caseId + ".";
        if (ownedAccount(c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }
        log.info("tool.getCaseStatus user={} case={}", principal.userId(), caseId);
        return "Case " + caseId + ": status " + c.status() + ", transaction " + c.transactionId()
                + " on account " + c.accountId() + ", reason: " + c.reason();
    }

    @Tool("Request a provisional credit for one of the customer's own dispute cases. "
            + "This does not move money; it creates a request for human approval by bank staff.")
    public String requestProvisionalCredit(@P("case id") String caseId,
                                           @P("credit amount in dollars") String amount) {
        dynamic();
        require("credit:request");
        audit.toolCalled("requestProvisionalCredit", principal.tenantId());
        status("Requesting provisional credit approval…");
        var c = banking.getCase(caseId);
        if (c == null) return "No dispute case found with id " + caseId + ".";
        if (ownedAccount(c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }
        
        var approval = banking.requestApproval(caseId, new BigDecimal(amount), principal.userId());
        log.info("tool.requestProvisionalCredit user={} case={} amount={} -> {} (approval {})",
                principal.userId(), caseId, amount, approval.status(), approval.approvalId());
        return "Provisional credit of $" + amount + " for case " + caseId
                + " is " + approval.status() + " (approval id " + approval.approvalId()
                + "). No funds have moved; bank staff will review it.";
    }
}
