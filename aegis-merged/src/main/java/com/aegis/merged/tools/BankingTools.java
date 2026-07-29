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
 * {@link ConfirmationGuard} tokens provide argument binding, not human-in-the-loop consent: they
 * guarantee the model cannot change a mutating call's arguments between describing it and
 * executing it, but the token round-trips through the tool-result string the model itself reads,
 * so nothing stops the model from calling a tool twice in the same turn with no user utterance in
 * between. Genuine consent needs the token to round-trip through the client (a dedicated SSE
 * event, an explicit Approve/Cancel UI control, POSTed back) rather than living entirely inside
 * one model turn. {@link #updateContactInfo} is a particular case worth calling out: changing the
 * fraud-alert contact channel is the classic account-takeover step, and a confirmation token held
 * by the model is not step-up auth — a real system sends an OTP to the existing contact first.
 */
@Component
public class BankingTools {

    private static final Logger log = LoggerFactory.getLogger(BankingTools.class);
    public static final String PRINCIPAL_KEY = "principal";
    
    public static final String DYNAMIC_ACCESS_KEY = "dynamicAccess";
    
    public static final String STATUS_KEY = "statusSink";
    
    public static final String TOOL_FAILED_KEY = "toolFailed";
    
    public static final String MUTATED_KEY = "mutated";
    
    public static final String CARDS_KEY = "cardsSink";
    
    public static final String ACCOUNTS_KEY = "accountsSink";
    
    public static final String TRANSACTIONS_KEY = "transactionsSink";
    
    public static final String CASES_KEY = "casesSink";
    
    public static final String APPROVALS_KEY = "approvalsSink";
    
    public static final String CITATIONS_KEY = "citationsSink";
    
    public static final String LEDGER_KEY = "ledgerSink";
    
    public static final String PROFILE_KEY = "profileSink";
    
    public static final String STATEMENT_KEY = "statementSink";

    public record Citation(String source, String snippet) {
    }

    public record SpendingSummary(String accountId, java.util.Map<String, java.math.BigDecimal> byCategory,
                                  java.math.BigDecimal totalDebits, java.math.BigDecimal totalCredits) {
    }

    @SuppressWarnings("unchecked")
    public static void status(ToolContext ctx, String message) {
        if (ctx.getContext().get(STATUS_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<String>) c).accept(message);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitCards(ToolContext ctx, java.util.List<BankingService.Card> cards) {
        if (ctx.getContext().get(CARDS_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<java.util.List<BankingService.Card>>) c).accept(cards);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitAccounts(ToolContext ctx, java.util.List<BankingService.Account> accounts) {
        if (ctx.getContext().get(ACCOUNTS_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<java.util.List<BankingService.Account>>) c).accept(accounts);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitTransactions(ToolContext ctx, java.util.List<BankingService.Transaction> txns) {
        if (ctx.getContext().get(TRANSACTIONS_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<java.util.List<BankingService.Transaction>>) c).accept(txns);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitCase(ToolContext ctx, BankingService.DisputeCase disputeCase) {
        if (ctx.getContext().get(CASES_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<BankingService.DisputeCase>) c).accept(disputeCase);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitApproval(ToolContext ctx, BankingService.Approval approval) {
        if (ctx.getContext().get(APPROVALS_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<BankingService.Approval>) c).accept(approval);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitCitations(ToolContext ctx, java.util.List<Citation> citations) {
        if (ctx.getContext().get(CITATIONS_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<java.util.List<Citation>>) c).accept(citations);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitLedger(ToolContext ctx, java.util.List<BankingService.LedgerEntry> entries) {
        if (ctx.getContext().get(LEDGER_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<java.util.List<BankingService.LedgerEntry>>) c).accept(entries);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitProfile(ToolContext ctx, BankingService.CustomerProfile profile) {
        if (ctx.getContext().get(PROFILE_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<BankingService.CustomerProfile>) c).accept(profile);
        }
    }

    @SuppressWarnings("unchecked")
    public static void emitStatement(ToolContext ctx, SpendingSummary summary) {
        if (ctx.getContext().get(STATEMENT_KEY) instanceof java.util.function.Consumer<?> c) {
            ((java.util.function.Consumer<SpendingSummary>) c).accept(summary);
        }
    }

    public static void markFailed(ToolContext ctx) {
        if (ctx.getContext().get(TOOL_FAILED_KEY) instanceof java.util.concurrent.atomic.AtomicBoolean b) {
            b.set(true);
        }
    }

    public static void markMutated(ToolContext ctx) {
        if (ctx.getContext().get(MUTATED_KEY) instanceof java.util.concurrent.atomic.AtomicBoolean b) {
            b.set(true);
        }
    }

    private static final BigDecimal MAX_SELF_TRANSFER = new BigDecimal("25000.00");

    static final String CONFIRMATION_TOKEN_PARAM_DESC =
            "Leave empty the first time. You'll get back a short confirmationToken code (like "
            + "\"K3F9QZ2R\") in a CONFIRMATION_REQUIRED reply — remember that code. After the "
            + "customer says yes, call this SAME tool again with this parameter set to that "
            + "exact code copied from the earlier reply. Do not leave it empty on that second call.";

    private static final java.util.Set<String> MERCHANT_CATEGORIES = java.util.Set.of(
            "GAMBLING", "INTERNATIONAL", "ONLINE", "ATM_CASH_ADVANCE", "ADULT_ENTERTAINMENT");

    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");

    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^\\+?[0-9()\\-. ]{7,20}$");
    private static final int MAX_NICKNAME_LENGTH = 40;

    private static final int MAX_TRAVEL_NOTICE_DAYS_AHEAD = 366;
    private static final int MAX_TRANSACTIONS_RETURNED = 20;
    private static final int MAX_CARDS_RETURNED = 20;

    private static java.util.List<BankingService.Transaction> mostRecent(
            java.util.List<BankingService.Transaction> txns, int limit) {
        return txns.stream()
                .sorted(java.util.Comparator.comparing(BankingService.Transaction::date).reversed())
                .limit(limit)
                .toList();
    }

    private final BankingService banking;
    private final com.aegis.merged.admin.AuditTrail audit;
    private final com.aegis.merged.kyc.KycProvider kyc;
    private final ConfirmationGuard confirmationGuard;

    public BankingTools(BankingService banking, com.aegis.merged.admin.AuditTrail audit,
                        com.aegis.merged.kyc.KycProvider kyc, ConfirmationGuard confirmationGuard) {
        this.banking = banking;
        this.audit = audit;
        this.kyc = kyc;
        this.confirmationGuard = confirmationGuard;
    }

    private Principal principal(ToolContext ctx) {

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
        emitAccounts(ctx, list);
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
        if (acct == null) { markFailed(ctx); return "No account found: " + accountId; }
        emitAccounts(ctx, java.util.List.of(acct));
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
        if (acct == null) { markFailed(ctx); return "No account found: " + accountId; }
        var all = banking.getTransactions(accountId);
        if (all.isEmpty()) { markFailed(ctx); return "No transactions for " + accountId; }
        var list = mostRecent(all, MAX_TRANSACTIONS_RETURNED);
        emitTransactions(ctx, list);
        StringBuilder sb = new StringBuilder("Transactions for " + accountId + ":\n");
        list.forEach(t -> sb.append("- ").append(t.txnId()).append(" ").append(t.date())
                .append(" $").append(t.amount()).append(" ").append(t.merchant()).append("\n"));
        if (all.size() > list.size()) {
            sb.append("(showing the ").append(list.size()).append(" most recent of ")
                    .append(all.size()).append(" total)\n");
        }
        log.info("tool.searchTransactions user={} account={} count={}", p.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool(description = "Open a dispute case for one of the customer's own transactions. The "
            + "transaction must exist on one of their accounts; if it doesn't, this fails.")
    public String createDisputeCase(@ToolParam(description = "transaction id, e.g. TXN-5001") String transactionId,
                                    @ToolParam(description = "reason for the dispute") String reason,
                                    @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC) String confirmationToken,
                                    ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cases:create");

        var txn = banking.findTransaction(transactionId);
        if (txn == null) {
            log.warn("tool.createDisputeCase.reject user={} reason=txn_not_found txn={}", p.userId(), transactionId);
            markFailed(ctx);
            audit.toolCalled("createDisputeCase", p.tenantId());
            return "No transaction found with id " + transactionId + ". Please check the id.";
        }
        if (ownedAccount(p, txn.accountId()) == null) {
            throw new AccessDeniedException("That transaction is not on one of your accounts.");
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "createDisputeCase", transactionId, reason)) {
            String token = confirmationGuard.issue(p.userId(), "createDisputeCase", transactionId, reason);
            audit.toolCalled("createDisputeCase:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": describe this back to the customer in your own "
                    + "words and ask them to confirm before calling this tool again with confirmationToken=\""
                    + token + "\" — Open a dispute for transaction "
                    + transactionId + " ($" + txn.amount() + " at " + txn.merchant() + ") on account "
                    + txn.accountId() + ", reason: " + reason + ".";
        }

        audit.toolCalled("createDisputeCase", p.tenantId());
        status(ctx, "Opening a dispute case for " + transactionId + "…");
        var c = banking.createCase(txn.accountId(), transactionId, reason);
        markMutated(ctx);
        emitCase(ctx, c);
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
        if (acct == null) { markFailed(ctx); return "No account found: " + accountId; }
        var all = banking.getCards(accountId);
        if (all.isEmpty()) { markFailed(ctx); return "No cards on " + accountId; }
        var list = all.size() > MAX_CARDS_RETURNED ? all.subList(0, MAX_CARDS_RETURNED) : all;
        emitCards(ctx, list);
        StringBuilder sb = new StringBuilder("Cards on " + accountId + ":\n");
        list.forEach(c -> sb.append("- ").append(c.cardId()).append(" ").append(c.network()).append(" ")
                .append(c.type()).append(" ****").append(c.last4()).append(" (").append(c.status()).append(")\n"));
        log.info("tool.listCards user={} account={} count={}", p.userId(), accountId, list.size());
        return sb.toString();
    }

    @Tool(description = "Freeze (block) one of the customer's own cards immediately, e.g. reported "
            + "lost/stolen or fraud suspected. Protective and reversible.")
    public String freezeCard(@ToolParam(description = "card id, e.g. CRD-7001") String cardId,
                             @ToolParam(description = "reason for the freeze") String reason,
                             @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC
                                     + " If they said \"my card\" without naming which one and they have more "
                                     + "than one, list their cards and ask which — never freeze more than the "
                                     + "one card they confirmed.")
                             String confirmationToken,
                             ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        var card = banking.findCard(cardId);
        if (card == null) {
            markFailed(ctx);
            audit.toolCalled("freezeCard", p.tenantId());
            return "No card found with id " + cardId + ".";
        }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        if ("FROZEN".equals(card.status())) return "Card " + cardId + " is already frozen.";

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "freezeCard", cardId)) {
            String token = confirmationGuard.issue(p.userId(), "freezeCard", cardId);
            audit.toolCalled("freezeCard:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to freeze card "
                    + cardId + " (" + card.type() + " ****" + card.last4() + ") and ask them to confirm before "
                    + "calling this tool again with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("freezeCard", p.tenantId());
        status(ctx, "Freezing card " + cardId + "…");
        var frozen = banking.freezeCard(cardId);
        if (frozen == null) { markFailed(ctx); return "Card " + cardId + " no longer exists."; }
        markMutated(ctx);
        emitCards(ctx, java.util.List.of(frozen));
        log.info("tool.freezeCard user={} card={} reason={}", p.userId(), cardId, reason);
        return "Card " + cardId + " (****" + frozen.last4() + ") is now FROZEN. Reason: " + reason
                + ". A replacement can be ordered if needed.";
    }

    @Tool(description = "Order a replacement for one of the customer's own cards (lost/stolen/damaged). "
            + "Creates a human-approval request; does not charge the customer directly.")
    public String requestCardReplacement(@ToolParam(description = "card id to replace") String cardId,
                                         @ToolParam(description = "reason for replacement") String reason,
                                         @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC)
                                         String confirmationToken,
                                         ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        var card = banking.findCard(cardId);
        if (card == null) {
            markFailed(ctx);
            audit.toolCalled("requestCardReplacement", p.tenantId());
            return "No card found with id " + cardId + ".";
        }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "requestCardReplacement", cardId)) {
            String token = confirmationGuard.issue(p.userId(), "requestCardReplacement", cardId);
            audit.toolCalled("requestCardReplacement:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer a replacement for card " + cardId
                    + " (" + card.type() + " ****" + card.last4() + ") carries a standard $5 fee (waivable per "
                    + "policy) and ask them to confirm before calling this tool again with confirmationToken=\""
                    + token + "\".";
        }

        audit.toolCalled("requestCardReplacement", p.tenantId());
        status(ctx, "Ordering a replacement for " + cardId + "…");
        
        var approval = banking.requestApproval(p.tenantId(), "CARD-REPLACEMENT:" + cardId,
                new BigDecimal("5.00"), p.userId());
        markMutated(ctx);
        emitApproval(ctx, approval);
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
        if (c == null) { markFailed(ctx); return "No dispute case found with id " + caseId + "."; }
        if (ownedAccount(p, c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }
        emitCase(ctx, c);
        log.info("tool.getCaseStatus user={} case={}", p.userId(), caseId);
        return "Case " + caseId + ": status " + c.status() + ", transaction " + c.transactionId()
                + " on account " + c.accountId() + ", reason: " + c.reason();
    }

    @Tool(description = "Request a provisional credit for one of the customer's own dispute cases. "
            + "This does not move money; it creates a request for human approval by bank staff.")
    public String issueProvisionalCredit(@ToolParam(description = "case id") String caseId,
                                         @ToolParam(description = "credit amount in dollars") String amount,
                                         @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC)
                                         String confirmationToken,
                                         ToolContext ctx) {
        var p = principal(ctx);
        require(p, "credit:request");

        var c = banking.getCase(caseId);
        if (c == null) {
            markFailed(ctx);
            audit.toolCalled("issueProvisionalCredit", p.tenantId());
            return "No dispute case found with id " + caseId + ".";
        }
        if (ownedAccount(p, c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }

        BigDecimal amt;
        try {
            amt = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            return "That doesn't look like a valid dollar amount.";
        }
        if (amt.signum() <= 0) {
            return "Credit amount must be a positive dollar amount.";
        }

        var disputedTxn = banking.findTransaction(c.transactionId());
        BigDecimal cap = disputedTxn != null
                ? disputedTxn.amount().multiply(new BigDecimal("1.5"))
                : MAX_SELF_TRANSFER;
        if (amt.compareTo(cap) > 0) {
            return "That exceeds what can be requested for this dispute (max $" + cap + ").";
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "issueProvisionalCredit", caseId, amt.toPlainString())) {
            String token = confirmationGuard.issue(p.userId(), "issueProvisionalCredit", caseId, amt.toPlainString());
            audit.toolCalled("issueProvisionalCredit:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to request a $" + amt
                    + " provisional credit for case " + caseId + " (bank staff still must approve it) and ask "
                    + "them to confirm before calling this tool again with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("issueProvisionalCredit", p.tenantId());
        status(ctx, "Requesting provisional credit approval…");
        
        var approval = banking.requestApproval(p.tenantId(), caseId, amt, p.userId());
        markMutated(ctx);
        emitApproval(ctx, approval);
        log.info("tool.issueProvisionalCredit user={} case={} amount={} -> {} (approval {})",
                p.userId(), caseId, amt, approval.status(), approval.approvalId());
        return "Provisional credit of $" + amt + " for case " + caseId
                + " is " + approval.status() + " (approval id " + approval.approvalId()
                + "). No funds have moved; bank staff will review it.";
    }

    @Tool(description = "Transfer money between two of the customer's OWN accounts (never to "
            + "someone else's account — that needs a different rail this demo doesn't have). "
            + "Posts a real double-entry ledger movement.")
    public String transferBetweenOwnAccounts(
            @ToolParam(description = "source account id") String fromAccountId,
            @ToolParam(description = "destination account id") String toAccountId,
            @ToolParam(description = "amount in dollars") String amount,
            @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC) String confirmationToken,
            ToolContext ctx) {
        var p = principal(ctx);
        require(p, "money:transfer");
        var from = ownedAccount(p, fromAccountId);
        var to = ownedAccount(p, toAccountId);
        if (from == null || to == null) {
            markFailed(ctx);
            audit.toolCalled("transferBetweenOwnAccounts", p.tenantId());
            return "Both accounts must be yours and must exist. Check the account ids.";
        }
        if (fromAccountId.equals(toAccountId)) {
            return "Source and destination must be different accounts.";
        }
        BigDecimal amt;
        try {
            amt = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            return "That doesn't look like a valid dollar amount.";
        }

        if (amt.signum() <= 0) {
            return "Transfer amount must be a positive dollar amount.";
        }
        if (amt.scale() > 2) {
            return "Amounts are limited to whole cents (at most 2 decimal places).";
        }
        if (amt.compareTo(MAX_SELF_TRANSFER) > 0) {
            return "That exceeds the per-transfer limit of $" + MAX_SELF_TRANSFER + ".";
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "transferBetweenOwnAccounts",
                fromAccountId, toAccountId, amt.toPlainString())) {
            String token = confirmationGuard.issue(p.userId(), "transferBetweenOwnAccounts",
                    fromAccountId, toAccountId, amt.toPlainString());
            audit.toolCalled("transferBetweenOwnAccounts:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to move $" + amt
                    + " from " + fromAccountId + " to " + toAccountId + " and ask them to confirm before calling "
                    + "this tool again with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("transferBetweenOwnAccounts", p.tenantId());
        status(ctx, "Transferring $" + amt + " to " + toAccountId + "…");
        try {
            var entries = banking.transfer(fromAccountId, toAccountId, amt,
                    "Transfer " + fromAccountId + " -> " + toAccountId);
            markMutated(ctx);
            emitLedger(ctx, entries);
            emitAccounts(ctx, java.util.List.of(banking.getAccount(fromAccountId), banking.getAccount(toAccountId)));
            log.info("tool.transfer user={} from={} to={} amount={}", p.userId(), fromAccountId, toAccountId, amt);
            return "Transferred $" + amt + " from " + fromAccountId + " to " + toAccountId + ". New balance on "
                    + fromAccountId + ": $" + banking.getAccount(fromAccountId).balance() + ".";
        } catch (IllegalStateException | IllegalArgumentException e) {
            markFailed(ctx);
            return e.getMessage();
        }
    }

    @Tool(description = "Get a spending summary / statement for one of the customer's own accounts: "
            + "totals by category, over recent transactions.")
    public String getSpendingSummary(@ToolParam(description = "account id") String accountId, ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:read");
        audit.toolCalled("getSpendingSummary", p.tenantId());
        status(ctx, "Building your statement for " + accountId + "…");
        var acct = ownedAccount(p, accountId);
        if (acct == null) { markFailed(ctx); return "No account found: " + accountId; }
        var all = banking.getTransactions(accountId);
        if (all.isEmpty()) { markFailed(ctx); return "No transactions to summarize for " + accountId; }
        var list = mostRecent(all, MAX_TRANSACTIONS_RETURNED);

        var byCategory = new java.util.TreeMap<String, BigDecimal>();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (var t : list) {
            if ("CREDIT".equals(t.direction())) {
                credits = credits.add(t.amount());
            } else {
                debits = debits.add(t.amount());
                byCategory.merge(categoryOf(t.merchant()), t.amount(), BigDecimal::add);
            }
        }
        var summary = new SpendingSummary(accountId, byCategory, debits, credits);
        emitStatement(ctx, summary);
        log.info("tool.getSpendingSummary user={} account={} categories={}", p.userId(), accountId, byCategory.size());

        StringBuilder sb = new StringBuilder("Spending summary for " + accountId + ":\n");
        byCategory.forEach((cat, amt) -> sb.append("- ").append(cat).append(": $").append(amt).append("\n"));
        sb.append("Total out: $").append(debits).append(", total in: $").append(credits);
        return sb.toString();
    }

    private static String categoryOf(String merchant) {
        String m = merchant.toLowerCase();
        if (m.contains("subscription")) return "Subscriptions";
        if (m.contains("atm") || m.contains("withdrawal")) return "Cash & ATM";
        if (m.contains("interest")) return "Interest";
        if (m.contains("transfer")) return "Transfers";
        if (m.contains("electronics") || m.contains("shop") || m.contains("store")) return "Shopping";
        return "Other";
    }

    @Tool(description = "Unfreeze one of the customer's own cards that is currently frozen.")
    public String unfreezeCard(@ToolParam(description = "card id") String cardId,
                               @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC) String confirmationToken,
                               ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        var card = banking.findCard(cardId);
        if (card == null) { markFailed(ctx); audit.toolCalled("unfreezeCard", p.tenantId()); return "No card found with id " + cardId + "."; }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        if (!"FROZEN".equals(card.status())) return "Card " + cardId + " is not frozen.";

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "unfreezeCard", cardId)) {
            String token = confirmationGuard.issue(p.userId(), "unfreezeCard", cardId);
            audit.toolCalled("unfreezeCard:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to unfreeze card "
                    + cardId + " (" + card.type() + " ****" + card.last4() + ") and ask them to confirm before "
                    + "calling this tool again with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("unfreezeCard", p.tenantId());
        status(ctx, "Unfreezing card " + cardId + "…");
        var updated = banking.unfreezeCard(cardId);
        if (updated == null) { markFailed(ctx); return "Card " + cardId + " no longer exists."; }
        markMutated(ctx);
        emitCards(ctx, java.util.List.of(updated));
        log.info("tool.unfreezeCard user={} card={}", p.userId(), cardId);
        return "Card " + cardId + " (****" + updated.last4() + ") is now ACTIVE.";
    }

    @Tool(description = "Set (or clear) a spending limit on one of the customer's own cards.")
    public String setCardSpendingLimit(@ToolParam(description = "card id") String cardId,
                                       @ToolParam(description = "limit in dollars, or \"none\" to clear it") String limit,
                                       @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC) String confirmationToken,
                                       ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        var card = banking.findCard(cardId);
        if (card == null) { markFailed(ctx); audit.toolCalled("setCardSpendingLimit", p.tenantId()); return "No card found with id " + cardId + "."; }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        BigDecimal parsed;
        if ("none".equalsIgnoreCase(limit.trim())) {
            parsed = null;
        } else {
            try {
                parsed = new BigDecimal(limit);
            } catch (NumberFormatException e) {
                return "That doesn't look like a valid dollar amount — use a number or \"none\".";
            }
            if (parsed.signum() <= 0) {
                return "Spending limit must be a positive dollar amount (or \"none\" to clear it).";
            }
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "setCardSpendingLimit", cardId, limit)) {
            String token = confirmationGuard.issue(p.userId(), "setCardSpendingLimit", cardId, limit);
            audit.toolCalled("setCardSpendingLimit:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to "
                    + (parsed == null ? "remove the spending limit" : "set a $" + parsed + " spending limit")
                    + " on card " + cardId + " and ask them to confirm before calling this tool again "
                    + "with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("setCardSpendingLimit", p.tenantId());
        status(ctx, "Updating spending limit for " + cardId + "…");
        var updated = banking.setSpendingLimit(cardId, parsed);
        if (updated == null) { markFailed(ctx); return "Card " + cardId + " no longer exists."; }
        markMutated(ctx);
        emitCards(ctx, java.util.List.of(updated));
        log.info("tool.setCardSpendingLimit user={} card={} limit={}", p.userId(), cardId, parsed);
        return parsed == null
                ? "Spending limit removed from card " + cardId + "."
                : "Card " + cardId + " now has a $" + parsed + " spending limit.";
    }

    @Tool(description = "Block or unblock a merchant category (e.g. GAMBLING, INTERNATIONAL, ONLINE) "
            + "on one of the customer's own cards.")
    public String toggleMerchantCategoryBlock(@ToolParam(description = "card id") String cardId,
                                              @ToolParam(description = "category, e.g. GAMBLING") String category,
                                              @ToolParam(description = "true to block, false to unblock") boolean blocked,
                                              @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC) String confirmationToken,
                                              ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        String normalizedCategory = category == null ? "" : category.trim().toUpperCase();
        if (!MERCHANT_CATEGORIES.contains(normalizedCategory)) {
            return "Unknown merchant category \"" + category + "\". Valid categories: "
                    + String.join(", ", MERCHANT_CATEGORIES) + ".";
        }
        var card = banking.findCard(cardId);
        if (card == null) { markFailed(ctx); audit.toolCalled("toggleMerchantCategoryBlock", p.tenantId()); return "No card found with id " + cardId + "."; }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "toggleMerchantCategoryBlock",
                cardId, normalizedCategory, blocked)) {
            String token = confirmationGuard.issue(p.userId(), "toggleMerchantCategoryBlock",
                    cardId, normalizedCategory, blocked);
            audit.toolCalled("toggleMerchantCategoryBlock:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to "
                    + (blocked ? "block " : "unblock ") + normalizedCategory + " on card " + cardId
                    + " and ask them to confirm before calling this tool again with confirmationToken=\""
                    + token + "\".";
        }

        audit.toolCalled("toggleMerchantCategoryBlock", p.tenantId());
        status(ctx, (blocked ? "Blocking " : "Unblocking ") + normalizedCategory + " on " + cardId + "…");
        var updated = banking.toggleMerchantCategory(cardId, normalizedCategory, blocked);
        if (updated == null) { markFailed(ctx); return "Card " + cardId + " no longer exists."; }
        markMutated(ctx);
        emitCards(ctx, java.util.List.of(updated));
        log.info("tool.toggleMerchantCategoryBlock user={} card={} category={} blocked={}",
                p.userId(), cardId, normalizedCategory, blocked);
        return "Card " + cardId + " " + (blocked ? "now blocks" : "no longer blocks") + " " + normalizedCategory + ".";
    }

    @Tool(description = "Update the customer's contact info (email and/or phone). Pass null/omit "
            + "a field to leave it unchanged.")
    public String updateContactInfo(@ToolParam(description = "new email, or omit") String email,
                                    @ToolParam(description = "new phone, or omit") String phone,
                                    @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC
                                            + " Contact info is also the fraud-alert channel, so treat it like any "
                                            + "other mutating action.")
                                    String confirmationToken,
                                    ToolContext ctx) {
        var p = principal(ctx);
        require(p, "profile:write");
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            return "That doesn't look like a valid email address.";
        }
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            return "That doesn't look like a valid phone number.";
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "updateContactInfo", email, phone)) {
            String token = confirmationGuard.issue(p.userId(), "updateContactInfo", email, phone);
            audit.toolCalled("updateContactInfo:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to update their "
                    + "contact info to" + (email != null ? " email " + email : "")
                    + (phone != null ? " phone " + phone : "") + " and ask them to confirm before calling this "
                    + "tool again with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("updateContactInfo", p.tenantId());
        status(ctx, "Updating contact info…");
        var updated = banking.updateContactInfo(p.tenantId(), p.userId(), email, phone);
        markMutated(ctx);
        emitProfile(ctx, updated);
        log.info("tool.updateContactInfo user={}", p.userId());
        return "Contact info updated. Email: " + updated.email() + ", phone: " + updated.phone() + ".";
    }

    @Tool(description = "Give one of the customer's own accounts a nickname/label. Purely cosmetic, no risk.")
    public String renameAccount(@ToolParam(description = "account id") String accountId,
                                @ToolParam(description = "new nickname") String nickname, ToolContext ctx) {
        var p = principal(ctx);
        require(p, "profile:write");
        if (nickname == null || nickname.isBlank()) {
            return "Nickname can't be empty.";
        }
        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            return "Nickname must be " + MAX_NICKNAME_LENGTH + " characters or fewer.";
        }
        var acct = ownedAccount(p, accountId);
        if (acct == null) { markFailed(ctx); audit.toolCalled("renameAccount", p.tenantId()); return "No account found: " + accountId; }
        audit.toolCalled("renameAccount", p.tenantId());
        var updated = banking.renameAccount(accountId, nickname.strip());
        if (updated == null) { markFailed(ctx); return "Account " + accountId + " no longer exists."; }
        markMutated(ctx);
        emitAccounts(ctx, java.util.List.of(updated));
        log.info("tool.renameAccount user={} account={} nickname={}", p.userId(), accountId, nickname);
        return "Account " + accountId + " is now labeled \"" + nickname + "\".";
    }

    @Tool(description = "Request closure of one of the customer's own accounts. Creates a human-approval "
            + "request; the account stays open until staff process it.")
    public String requestAccountClosure(@ToolParam(description = "account id") String accountId,
                                        @ToolParam(description = "reason for closing") String reason,
                                        @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC)
                                        String confirmationToken,
                                        ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:write");
        var acct = ownedAccount(p, accountId);
        if (acct == null) { markFailed(ctx); audit.toolCalled("requestAccountClosure", p.tenantId()); return "No account found: " + accountId; }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "requestAccountClosure", accountId)) {
            String token = confirmationGuard.issue(p.userId(), "requestAccountClosure", accountId);
            audit.toolCalled("requestAccountClosure:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to submit a "
                    + "closure request for " + accountId + " (current balance $" + acct.balance() + " — closure "
                    + "typically requires a zero balance) and ask them to confirm before calling this tool again "
                    + "with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("requestAccountClosure", p.tenantId());
        status(ctx, "Submitting closure request for " + accountId + "…");

        var approval = banking.requestApproval(p.tenantId(), "ACCOUNT-CLOSURE:" + accountId,
                BigDecimal.ZERO, p.userId());
        markMutated(ctx);
        emitApproval(ctx, approval);
        log.info("tool.requestAccountClosure user={} account={} approval={}", p.userId(), accountId, approval.approvalId());
        return "Closure request submitted for " + accountId + " (approval id " + approval.approvalId()
                + "). Bank staff will confirm the balance is zero before finalizing. Reason: " + reason;
    }

    @Tool(description = "Set the customer's alert preferences (low balance, large transaction). No risk, no confirmation needed.")
    public String setAlertPreferences(@ToolParam(description = "true to get low-balance alerts") boolean lowBalance,
                                      @ToolParam(description = "true to get large-transaction alerts") boolean largeTransaction,
                                      ToolContext ctx) {
        var p = principal(ctx);
        require(p, "profile:write");
        audit.toolCalled("setAlertPreferences", p.tenantId());
        var updated = banking.setAlertPreferences(p.tenantId(), p.userId(), lowBalance, largeTransaction);
        markMutated(ctx);
        emitProfile(ctx, updated);
        log.info("tool.setAlertPreferences user={} lowBalance={} largeTxn={}", p.userId(), lowBalance, largeTransaction);
        return "Alert preferences updated: low-balance " + (lowBalance ? "on" : "off")
                + ", large-transaction " + (largeTransaction ? "on" : "off") + ".";
    }

    @Tool(description = "Set a travel notice so card usage abroad isn't flagged as suspicious. "
            + "No risk, no confirmation needed.")
    public String setTravelNotice(@ToolParam(description = "last day of travel, e.g. 2026-08-15") String until,
                                  @ToolParam(description = "destination country/region") String destination,
                                  ToolContext ctx) {
        var p = principal(ctx);
        require(p, "profile:write");
        audit.toolCalled("setTravelNotice", p.tenantId());
        java.time.LocalDate untilDate;
        try {
            untilDate = java.time.LocalDate.parse(until);
        } catch (Exception e) {
            return "That doesn't look like a valid date (use YYYY-MM-DD).";
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        if (untilDate.isBefore(today)) {
            return "That date is in the past — a travel notice needs a future end date.";
        }
        if (untilDate.isAfter(today.plusDays(MAX_TRAVEL_NOTICE_DAYS_AHEAD))) {
            return "Travel notices can be set at most " + MAX_TRAVEL_NOTICE_DAYS_AHEAD + " days out.";
        }
        var updated = banking.setTravelNotice(p.tenantId(), p.userId(), untilDate, destination);
        markMutated(ctx);
        emitProfile(ctx, updated);
        log.info("tool.setTravelNotice user={} until={} destination={}", p.userId(), untilDate, destination);
        return "Travel notice set for " + destination + " through " + untilDate + ".";
    }

    @Tool(description = "Attach a note that supporting evidence was provided for one of the customer's "
            + "own dispute cases (receipt, correspondence, etc). No risk, no confirmation needed.")
    public String addCaseEvidence(@ToolParam(description = "case id") String caseId,
                                  @ToolParam(description = "brief description of the evidence") String description,
                                  ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cases:create");
        var c = banking.getCase(caseId);
        if (c == null) { markFailed(ctx); audit.toolCalled("addCaseEvidence", p.tenantId()); return "No dispute case found with id " + caseId + "."; }
        if (ownedAccount(p, c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }
        audit.toolCalled("addCaseEvidence", p.tenantId());
        var updated = banking.addEvidence(caseId);
        if (updated == null) { markFailed(ctx); return "Case " + caseId + " no longer exists."; }
        markMutated(ctx);
        emitCase(ctx, updated);
        log.info("tool.addCaseEvidence user={} case={} description={}", p.userId(), caseId, description);
        return "Noted: " + description + ". Case " + caseId + " now has " + updated.evidenceCount() + " piece(s) of evidence on file.";
    }

    @Tool(description = "Escalate one of the customer's own dispute cases to a human agent for priority review.")
    public String escalateCase(@ToolParam(description = "case id") String caseId, ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cases:create");
        var c = banking.getCase(caseId);
        if (c == null) { markFailed(ctx); audit.toolCalled("escalateCase", p.tenantId()); return "No dispute case found with id " + caseId + "."; }
        if (ownedAccount(p, c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }
        audit.toolCalled("escalateCase", p.tenantId());
        status(ctx, "Escalating " + caseId + " to a human agent…");
        var updated = banking.escalateCase(caseId);
        if (updated == null) { markFailed(ctx); return "Case " + caseId + " no longer exists."; }
        markMutated(ctx);
        emitCase(ctx, updated);
        log.info("tool.escalateCase user={} case={}", p.userId(), caseId);
        return "Case " + caseId + " escalated — a human agent will follow up directly.";
    }

    @Tool(description = "Report suspected FRAUD on a transaction (distinct from an ordinary dispute — "
            + "same-day priority review vs. the standard dispute window). Opens a fraud-flagged case.")
    public String reportFraud(@ToolParam(description = "transaction id") String transactionId,
                              @ToolParam(description = "what looked fraudulent") String description,
                              @ToolParam(description = CONFIRMATION_TOKEN_PARAM_DESC) String confirmationToken,
                              ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cases:create");
        var txn = banking.findTransaction(transactionId);
        if (txn == null) {
            markFailed(ctx);
            audit.toolCalled("reportFraud", p.tenantId());
            return "No transaction found with id " + transactionId + ". Please check the id.";
        }
        if (ownedAccount(p, txn.accountId()) == null) {
            throw new AccessDeniedException("That transaction is not on one of your accounts.");
        }

        if (!confirmationGuard.verify(confirmationToken, p.userId(), "reportFraud", transactionId, description)) {
            String token = confirmationGuard.issue(p.userId(), "reportFraud", transactionId, description);
            audit.toolCalled("reportFraud:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED token=" + token + ": tell the customer you're about to file a FRAUD "
                    + "report for " + transactionId + " ($" + txn.amount() + " at " + txn.merchant() + ") — this "
                    + "gets same-day priority review — and ask them to confirm before calling this tool again "
                    + "with confirmationToken=\"" + token + "\".";
        }

        audit.toolCalled("reportFraud", p.tenantId());
        status(ctx, "Filing a fraud report for " + transactionId + "…");
        var c = banking.createCase(txn.accountId(), transactionId, description, true);
        markMutated(ctx);
        emitCase(ctx, c);
        log.info("tool.reportFraud user={} case={} txn={}", p.userId(), c.caseId(), transactionId);
        return "Fraud report filed: case " + c.caseId() + " for transaction " + transactionId
                + ". This is flagged for same-day priority review, separate from the standard dispute queue.";
    }

    @Tool(description = "Request identity re-verification for the customer (e.g. after a suspected "
            + "account takeover, or a KYC tier upgrade). Submits to identity review — never resolves "
            + "immediately.")
    public String requestIdentityReVerification(@ToolParam(description = "why re-verification is needed") String reason,
                                                 ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:write");
        audit.toolCalled("requestIdentityReVerification", p.tenantId());
        status(ctx, "Submitting identity verification request…");
        var result = kyc.verify(p.tenantId(), p.userId(), reason);
        log.info("tool.requestIdentityReVerification user={} ref={} outcome={}",
                p.userId(), result.referenceId(), result.outcome());
        return "Identity verification requested (reference " + result.referenceId() + "): " + result.detail();
    }
}
