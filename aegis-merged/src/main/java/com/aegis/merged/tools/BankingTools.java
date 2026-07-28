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
    /** A caller can pass an AtomicBoolean here; tools flip it true whenever a lookup comes
        back empty/not-found, so the router can escalate the NEXT turn even if the model's
        own phrasing of that failure happens to dodge {@link com.aegis.merged.assistant.AnswerConfidence}. */
    public static final String TOOL_FAILED_KEY = "toolFailed";
    /** A caller can pass an AtomicBoolean here; tools flip it true whenever they actually
        change state (freeze/unfreeze a card, post a transfer, file a case, update a profile —
        anything beyond a read). AssistantController uses this to refuse to retry a
        truncated-looking answer once a real mutation already ran this turn: a blind retry
        would replay the same tool call and could freeze/transfer/file the same thing twice. */
    public static final String MUTATED_KEY = "mutated";
    /** Optional Consumer&lt;List&lt;Card&gt;&gt;: listCards pushes the STRUCTURED card data here
        so the frontend can render a real card UI (network branding, masked number, status)
        instead of parsing it back out of the model's prose — the model still narrates in
        text, but the actual data for the widget comes straight from the tool, not the LLM. */
    public static final String CARDS_KEY = "cardsSink";
    /** Same pattern as CARDS_KEY, for account balance tiles. */
    public static final String ACCOUNTS_KEY = "accountsSink";
    /** Same pattern as CARDS_KEY, for the transaction list widget. */
    public static final String TRANSACTIONS_KEY = "transactionsSink";
    /** Same pattern as CARDS_KEY, for the dispute-case status card. */
    public static final String CASES_KEY = "casesSink";
    /** Same pattern as CARDS_KEY, for the approval-receipt card (card replacement fee,
        provisional credit) — both create a PENDING_HUMAN_APPROVAL request, never move
        money directly. */
    public static final String APPROVALS_KEY = "approvalsSink";
    /** Same pattern as CARDS_KEY, for policy-answer citation chips. */
    public static final String CITATIONS_KEY = "citationsSink";
    /** Same pattern as CARDS_KEY, for a transfer receipt (the two LedgerEntry rows it posted). */
    public static final String LEDGER_KEY = "ledgerSink";
    /** Same pattern as CARDS_KEY, for the customer profile widget (contact info, alert
        preferences, travel notice). */
    public static final String PROFILE_KEY = "profileSink";
    /** Same pattern as CARDS_KEY, for the spending-summary/statement widget. */
    public static final String STATEMENT_KEY = "statementSink";

    /** One cited policy passage — source document + a short snippet, pushed by
        PolicySearchTool alongside the model's prose answer. */
    public record Citation(String source, String snippet) {
    }

    /** A lightweight category breakdown derived from transaction merchant names — not a
        fabricated fact, a computed aggregation of real transaction data the customer already
        owns. Real categorization in production comes from the card network's MCC codes, not
        merchant-name keyword matching. */
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

    /** No documented daily/per-transfer limit exists elsewhere in this demo, so this is a
        conservative placeholder — a real deployment sets this per product/risk policy, not
        as a single hardcoded constant. */
    private static final BigDecimal MAX_SELF_TRANSFER = new BigDecimal("25000.00");

    private final BankingService banking;
    private final com.aegis.merged.admin.AuditTrail audit;
    private final com.aegis.merged.kyc.KycProvider kyc;

    public BankingTools(BankingService banking, com.aegis.merged.admin.AuditTrail audit,
                        com.aegis.merged.kyc.KycProvider kyc) {
        this.banking = banking;
        this.audit = audit;
        this.kyc = kyc;
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
        var list = banking.getTransactions(accountId);
        if (list.isEmpty()) { markFailed(ctx); return "No transactions for " + accountId; }
        emitTransactions(ctx, list);
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
                                    @ToolParam(description = "true ONLY if the customer has already explicitly "
                                            + "confirmed THIS EXACT dispute (transaction + reason) earlier in this "
                                            + "conversation, e.g. said \"yes\" after you described it back to them; "
                                            + "false or omitted on the first attempt") Boolean confirmed,
                                    ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cases:create");

        // Validate the transaction exists AND is on an account the caller owns BEFORE
        // describing or creating a case — never confirm or dispute a foreign transaction id.
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

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("createDisputeCase:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: describe this back to the customer in your own words and ask them to "
                    + "confirm before calling this tool again with confirmed=true — Open a dispute for transaction "
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
        var list = banking.getCards(accountId);
        if (list.isEmpty()) { markFailed(ctx); return "No cards on " + accountId; }
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
                             @ToolParam(description = "true ONLY if the customer has already explicitly confirmed "
                                     + "freezing THIS EXACT card earlier in this conversation, e.g. said \"yes\" "
                                     + "after you named the card back to them; false or omitted on the first "
                                     + "attempt. If they said \"my card\" without naming which one and they have "
                                     + "more than one, list their cards and ask which — never freeze more than "
                                     + "the one card they confirmed.")
                             Boolean confirmed,
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

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("freezeCard:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer you're about to freeze card " + cardId
                    + " (" + card.type() + " ****" + card.last4() + ") and ask them to confirm before calling "
                    + "this tool again with confirmed=true.";
        }

        audit.toolCalled("freezeCard", p.tenantId());
        status(ctx, "Freezing card " + cardId + "…");
        var frozen = banking.freezeCard(cardId);
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
                                         @ToolParam(description = "true ONLY if the customer has already "
                                                 + "explicitly confirmed replacing THIS EXACT card earlier in "
                                                 + "this conversation; false or omitted on the first attempt")
                                         Boolean confirmed,
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

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("requestCardReplacement:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer a replacement for card " + cardId
                    + " (" + card.type() + " ****" + card.last4() + ") carries a standard $5 fee (waivable per "
                    + "policy) and ask them to confirm before calling this tool again with confirmed=true.";
        }

        audit.toolCalled("requestCardReplacement", p.tenantId());
        status(ctx, "Ordering a replacement for " + cardId + "…");
        // Standard replacement fee per card-services policy; waivable by the approver.
        var approval = banking.requestApproval("CARD-REPLACEMENT:" + cardId,
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
                                         @ToolParam(description = "true ONLY if the customer has already "
                                                 + "explicitly confirmed THIS EXACT amount and case earlier in "
                                                 + "this conversation; false or omitted on the first attempt")
                                         Boolean confirmed,
                                         ToolContext ctx) {
        var p = principal(ctx);
        require(p, "credit:request");
        // Ownership check backported from the LC4j build — the original allowed a
        // credit request against any case id.
        var c = banking.getCase(caseId);
        if (c == null) {
            markFailed(ctx);
            audit.toolCalled("issueProvisionalCredit", p.tenantId());
            return "No dispute case found with id " + caseId + ".";
        }
        if (ownedAccount(p, c.accountId()) == null) {
            throw new AccessDeniedException("That case is not on one of your accounts.");
        }

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("issueProvisionalCredit:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer you're about to request a $" + amount
                    + " provisional credit for case " + caseId + " (bank staff still must approve it) and ask "
                    + "them to confirm before calling this tool again with confirmed=true.";
        }

        audit.toolCalled("issueProvisionalCredit", p.tenantId());
        status(ctx, "Requesting provisional credit approval…");
        // Human-in-the-loop: the model can REQUEST money movement, never EXECUTE it.
        var approval = banking.requestApproval(caseId, new BigDecimal(amount), p.userId());
        markMutated(ctx);
        emitApproval(ctx, approval);
        log.info("tool.issueProvisionalCredit user={} case={} amount={} -> {} (approval {})",
                p.userId(), caseId, amount, approval.status(), approval.approvalId());
        return "Provisional credit of $" + amount + " for case " + caseId
                + " is " + approval.status() + " (approval id " + approval.approvalId()
                + "). No funds have moved; bank staff will review it.";
    }

    // --- Phase 1: payments ---------------------------------------------------------------

    @Tool(description = "Transfer money between two of the customer's OWN accounts (never to "
            + "someone else's account — that needs a different rail this demo doesn't have). "
            + "Posts a real double-entry ledger movement.")
    public String transferBetweenOwnAccounts(
            @ToolParam(description = "source account id") String fromAccountId,
            @ToolParam(description = "destination account id") String toAccountId,
            @ToolParam(description = "amount in dollars") String amount,
            @ToolParam(description = "true ONLY if the customer has already explicitly confirmed "
                    + "THIS EXACT transfer (amount + both accounts) earlier in this conversation; "
                    + "false or omitted on the first attempt") Boolean confirmed,
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
        // Fail fast with a friendly message before the confirmation round-trip — a negative
        // amount would otherwise pass the domain layer's funds check (2500 >= -100) and
        // subtract(-100) CREDITS the source while debiting the destination, reversing the
        // transfer's direction. BankingService.transfer also rejects this defensively.
        if (amt.signum() <= 0) {
            return "Transfer amount must be a positive dollar amount.";
        }
        if (amt.scale() > 2) {
            return "Amounts are limited to whole cents (at most 2 decimal places).";
        }
        if (amt.compareTo(MAX_SELF_TRANSFER) > 0) {
            return "That exceeds the per-transfer limit of $" + MAX_SELF_TRANSFER + ".";
        }

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("transferBetweenOwnAccounts:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer you're about to move $" + amt + " from "
                    + fromAccountId + " to " + toAccountId + " and ask them to confirm before calling "
                    + "this tool again with confirmed=true.";
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
        var list = banking.getTransactions(accountId);
        if (list.isEmpty()) { markFailed(ctx); return "No transactions to summarize for " + accountId; }

        var byCategory = new java.util.TreeMap<String, BigDecimal>();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (var t : list) {
            String category = categoryOf(t.merchant());
            byCategory.merge(category, t.amount(), BigDecimal::add);
            if ("CREDIT".equals(t.direction())) credits = credits.add(t.amount());
            else debits = debits.add(t.amount());
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

    // --- Phase 1: card controls -----------------------------------------------------------

    @Tool(description = "Unfreeze one of the customer's own cards that is currently frozen.")
    public String unfreezeCard(@ToolParam(description = "card id") String cardId,
                               @ToolParam(description = "true ONLY if the customer has already explicitly "
                                       + "confirmed unfreezing THIS EXACT card earlier in this conversation; "
                                       + "false or omitted on the first attempt") Boolean confirmed,
                               ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        var card = banking.findCard(cardId);
        if (card == null) { markFailed(ctx); audit.toolCalled("unfreezeCard", p.tenantId()); return "No card found with id " + cardId + "."; }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        if (!"FROZEN".equals(card.status())) return "Card " + cardId + " is not frozen.";

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("unfreezeCard:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer you're about to unfreeze card " + cardId
                    + " (" + card.type() + " ****" + card.last4() + ") and ask them to confirm before calling "
                    + "this tool again with confirmed=true.";
        }

        audit.toolCalled("unfreezeCard", p.tenantId());
        status(ctx, "Unfreezing card " + cardId + "…");
        var updated = banking.unfreezeCard(cardId);
        markMutated(ctx);
        emitCards(ctx, java.util.List.of(updated));
        log.info("tool.unfreezeCard user={} card={}", p.userId(), cardId);
        return "Card " + cardId + " (****" + updated.last4() + ") is now ACTIVE.";
    }

    @Tool(description = "Set (or clear) a spending limit on one of the customer's own cards.")
    public String setCardSpendingLimit(@ToolParam(description = "card id") String cardId,
                                       @ToolParam(description = "limit in dollars, or \"none\" to clear it") String limit,
                                       ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        var card = banking.findCard(cardId);
        if (card == null) { markFailed(ctx); audit.toolCalled("setCardSpendingLimit", p.tenantId()); return "No card found with id " + cardId + "."; }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        audit.toolCalled("setCardSpendingLimit", p.tenantId());
        BigDecimal parsed = "none".equalsIgnoreCase(limit.trim()) ? null : new BigDecimal(limit);
        status(ctx, "Updating spending limit for " + cardId + "…");
        var updated = banking.setSpendingLimit(cardId, parsed);
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
                                              ToolContext ctx) {
        var p = principal(ctx);
        require(p, "cards:manage");
        var card = banking.findCard(cardId);
        if (card == null) { markFailed(ctx); audit.toolCalled("toggleMerchantCategoryBlock", p.tenantId()); return "No card found with id " + cardId + "."; }
        if (ownedAccount(p, card.accountId()) == null) {
            throw new AccessDeniedException("That card is not on one of your accounts.");
        }
        audit.toolCalled("toggleMerchantCategoryBlock", p.tenantId());
        status(ctx, (blocked ? "Blocking " : "Unblocking ") + category + " on " + cardId + "…");
        var updated = banking.toggleMerchantCategory(cardId, category, blocked);
        markMutated(ctx);
        emitCards(ctx, java.util.List.of(updated));
        log.info("tool.toggleMerchantCategoryBlock user={} card={} category={} blocked={}",
                p.userId(), cardId, category, blocked);
        return "Card " + cardId + " " + (blocked ? "now blocks" : "no longer blocks") + " " + category.toUpperCase() + ".";
    }

    // --- Phase 1: profile ------------------------------------------------------------------

    @Tool(description = "Update the customer's contact info (email and/or phone). Pass null/omit "
            + "a field to leave it unchanged.")
    public String updateContactInfo(@ToolParam(description = "new email, or omit") String email,
                                    @ToolParam(description = "new phone, or omit") String phone,
                                    @ToolParam(description = "true ONLY if the customer has already explicitly "
                                            + "confirmed THIS EXACT change earlier in this conversation — contact "
                                            + "info is also the fraud-alert channel, so treat it like any other "
                                            + "mutating action; false or omitted on the first attempt")
                                    Boolean confirmed,
                                    ToolContext ctx) {
        var p = principal(ctx);
        require(p, "profile:write");

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("updateContactInfo:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer you're about to update their contact info to"
                    + (email != null ? " email " + email : "") + (phone != null ? " phone " + phone : "")
                    + " and ask them to confirm before calling this tool again with confirmed=true.";
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
        var acct = ownedAccount(p, accountId);
        if (acct == null) { markFailed(ctx); audit.toolCalled("renameAccount", p.tenantId()); return "No account found: " + accountId; }
        audit.toolCalled("renameAccount", p.tenantId());
        var updated = banking.renameAccount(accountId, nickname);
        markMutated(ctx);
        emitAccounts(ctx, java.util.List.of(updated));
        log.info("tool.renameAccount user={} account={} nickname={}", p.userId(), accountId, nickname);
        return "Account " + accountId + " is now labeled \"" + nickname + "\".";
    }

    @Tool(description = "Request closure of one of the customer's own accounts. Creates a human-approval "
            + "request; the account stays open until staff process it.")
    public String requestAccountClosure(@ToolParam(description = "account id") String accountId,
                                        @ToolParam(description = "reason for closing") String reason,
                                        @ToolParam(description = "true ONLY if the customer has already explicitly "
                                                + "confirmed closing THIS EXACT account earlier in this conversation; "
                                                + "false or omitted on the first attempt") Boolean confirmed,
                                        ToolContext ctx) {
        var p = principal(ctx);
        require(p, "account:write");
        var acct = ownedAccount(p, accountId);
        if (acct == null) { markFailed(ctx); audit.toolCalled("requestAccountClosure", p.tenantId()); return "No account found: " + accountId; }

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("requestAccountClosure:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer you're about to submit a closure request for "
                    + accountId + " (current balance $" + acct.balance() + " — closure typically requires a "
                    + "zero balance) and ask them to confirm before calling this tool again with confirmed=true.";
        }

        audit.toolCalled("requestAccountClosure", p.tenantId());
        status(ctx, "Submitting closure request for " + accountId + "…");
        var approval = banking.requestApproval("ACCOUNT-CLOSURE:" + accountId, acct.balance(), p.userId());
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
        var updated = banking.setTravelNotice(p.tenantId(), p.userId(), untilDate, destination);
        markMutated(ctx);
        emitProfile(ctx, updated);
        log.info("tool.setTravelNotice user={} until={} destination={}", p.userId(), untilDate, destination);
        return "Travel notice set for " + destination + " through " + untilDate + ".";
    }

    // --- Phase 1: disputes & fraud ---------------------------------------------------------

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
        markMutated(ctx);
        emitCase(ctx, updated);
        log.info("tool.escalateCase user={} case={}", p.userId(), caseId);
        return "Case " + caseId + " escalated — a human agent will follow up directly.";
    }

    @Tool(description = "Report suspected FRAUD on a transaction (distinct from an ordinary dispute — "
            + "same-day priority review vs. the standard dispute window). Opens a fraud-flagged case.")
    public String reportFraud(@ToolParam(description = "transaction id") String transactionId,
                              @ToolParam(description = "what looked fraudulent") String description,
                              @ToolParam(description = "true ONLY if the customer has already explicitly "
                                      + "confirmed THIS EXACT fraud report earlier in this conversation; "
                                      + "false or omitted on the first attempt") Boolean confirmed,
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

        if (!Boolean.TRUE.equals(confirmed)) {
            audit.toolCalled("reportFraud:pending-confirmation", p.tenantId());
            return "CONFIRMATION_REQUIRED: tell the customer you're about to file a FRAUD report for "
                    + transactionId + " ($" + txn.amount() + " at " + txn.merchant() + ") — this gets "
                    + "same-day priority review — and ask them to confirm before calling this tool again "
                    + "with confirmed=true.";
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
