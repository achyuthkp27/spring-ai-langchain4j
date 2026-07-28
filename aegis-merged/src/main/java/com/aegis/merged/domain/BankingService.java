package com.aegis.merged.domain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory banking domain stand-in so the assistant has real state to act on.
 * Accounts carry an ownerUserId: the customer-facing bot may only ever touch the
 * authenticated customer's own accounts (enforced inside the tools).
 *
 * Money-adjacent state (balances) is backed by a real double-entry ledger — see
 * {@link LedgerEntry} and {@link #transfer} — rather than a balance field mutated in place,
 * so every change is reconstructable and reconcilable, which is the actual production-grade
 * bar for anything touching money (Phase 2 of the roadmap). Everything else here is still a
 * simplified fixture: a real deployment replaces this whole class with a real core banking
 * platform or ledger-as-a-service, not just a bigger version of this one.
 */
@Service
public class BankingService {

    public record Account(String accountId, String tenantId, String ownerUserId,
                          BigDecimal balance, String type, String nickname) {
    }

    public record Transaction(String txnId, String accountId, LocalDate date,
                              BigDecimal amount, String merchant, String direction) {
    }

    public record Card(String cardId, String accountId, String type, String network, String last4,
                       String status, BigDecimal spendingLimit, Set<String> blockedCategories) {
    }

    public record DisputeCase(String caseId, String accountId, String transactionId, String reason,
                              String status, boolean fraudFlag, boolean escalated, int evidenceCount) {
    }

    public record Approval(String approvalId, String subject, BigDecimal amount,
                           String requestedBy, String status) {
    }

    /** One posted ledger movement. Every balance change in this system exists because of one
        of these — there is no other way to move a balance. Two entries (a DEBIT and a CREDIT)
        sharing the same {@code reference} form one double-entry transfer, so the ledger always
        nets to zero across the pair and either can be traced back to the other. */
    public record LedgerEntry(String entryId, String accountId, BigDecimal amount, String direction,
                              BigDecimal balanceAfter, String reference, Instant postedAt) {
    }

    public record CustomerProfile(String tenantId, String userId, String email, String phone,
                                  boolean lowBalanceAlerts, boolean largeTransactionAlerts,
                                  LocalDate travelNoticeUntil, String travelDestination) {
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> txns = new ConcurrentHashMap<>();
    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final Map<String, DisputeCase> cases = new ConcurrentHashMap<>();
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<LedgerEntry>> ledger = new ConcurrentHashMap<>();
    private final Map<String, CustomerProfile> profiles = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    public BankingService() {
        accounts.put("ACC-1001", new Account("ACC-1001", "achu-bank", "demo-user", new BigDecimal("2500.00"), "CHECKING", null));
        accounts.put("ACC-1002", new Account("ACC-1002", "achu-bank", "demo-user", new BigDecimal("15750.25"), "SAVINGS", null));
        accounts.put("ACC-9001", new Account("ACC-9001", "globex-bank", "globex-user", new BigDecimal("99.00"), "CHECKING", null));
        txns.put("ACC-1001", new CopyOnWriteArrayList<>(List.of(
                new Transaction("TXN-5001", "ACC-1001", LocalDate.now().minusDays(2),
                        new BigDecimal("49.99"), "StreamCo Subscription", "DEBIT"),
                new Transaction("TXN-5002", "ACC-1001", LocalDate.now().minusDays(2),
                        new BigDecimal("49.99"), "StreamCo Subscription", "DEBIT"),
                new Transaction("TXN-5003", "ACC-1001", LocalDate.now().minusDays(1),
                        new BigDecimal("2000.00"), "Foreign Electronics Ltd", "DEBIT")
        )));
        txns.put("ACC-1002", new CopyOnWriteArrayList<>(List.of(
                new Transaction("TXN-6001", "ACC-1002", LocalDate.now().minusDays(5),
                        new BigDecimal("29.53"), "Interest Credit", "CREDIT"),
                new Transaction("TXN-6002", "ACC-1002", LocalDate.now().minusDays(3),
                        new BigDecimal("400.00"), "ATM Withdrawal - Main St", "DEBIT")
        )));
        cards.put("CRD-7001", new Card("CRD-7001", "ACC-1001", "DEBIT", "VISA", "4412", "ACTIVE", null, Set.of()));
        cards.put("CRD-7002", new Card("CRD-7002", "ACC-1001", "CREDIT", "MASTERCARD", "8830", "ACTIVE", null, Set.of()));
        cards.put("CRD-7003", new Card("CRD-7003", "ACC-1002", "DEBIT", "VISA", "1177", "ACTIVE", null, Set.of()));
        cards.put("CRD-9001", new Card("CRD-9001", "ACC-9001", "DEBIT", "MASTERCARD", "6021", "ACTIVE", null, Set.of()));
        profiles.put("achu-bank:demo-user", new CustomerProfile("achu-bank", "demo-user",
                "demo-user@example.com", "+1-555-0100", true, true, null, null));
        profiles.put("globex-bank:globex-user", new CustomerProfile("globex-bank", "globex-user",
                "globex-user@example.com", "+1-555-0200", true, true, null, null));
    }

    public Account getAccount(String accountId) {
        return accounts.get(accountId);
    }

    /** All accounts owned by this user within their tenant — "my accounts". */
    public List<Account> accountsOf(String tenantId, String ownerUserId) {
        return accounts.values().stream()
                .filter(a -> a.tenantId().equals(tenantId) && a.ownerUserId().equals(ownerUserId))
                .sorted(java.util.Comparator.comparing(Account::accountId))
                .toList();
    }

    /** Admin/AML-scan use only — every account in a tenant, regardless of owner. Customer-facing
        tools must never call this; they go through {@link #accountsOf} (owner-scoped). */
    public List<Account> allAccountsForTenant(String tenantId) {
        return accounts.values().stream()
                .filter(a -> a.tenantId().equals(tenantId))
                .sorted(java.util.Comparator.comparing(Account::accountId))
                .toList();
    }

    public Account renameAccount(String accountId, String nickname) {
        return accounts.computeIfPresent(accountId, (k, a) ->
                new Account(a.accountId(), a.tenantId(), a.ownerUserId(), a.balance(), a.type(), nickname));
    }

    public List<Transaction> getTransactions(String accountId) {
        return txns.getOrDefault(accountId, new CopyOnWriteArrayList<>());
    }

    /** Find a transaction by id across all accounts (returns null if it doesn't exist). */
    public Transaction findTransaction(String transactionId) {
        return txns.values().stream()
                .flatMap(List::stream)
                .filter(t -> t.txnId().equals(transactionId))
                .findFirst()
                .orElse(null);
    }

    public List<Card> getCards(String accountId) {
        return cards.values().stream()
                .filter(c -> c.accountId().equals(accountId))
                .sorted(java.util.Comparator.comparing(Card::cardId))
                .toList();
    }

    public Card findCard(String cardId) {
        return cards.get(cardId);
    }

    /** Protective action — immediate, reversible by staff, always audited by the caller. */
    public Card freezeCard(String cardId) {
        return cards.computeIfPresent(cardId, (k, c) ->
                new Card(c.cardId(), c.accountId(), c.type(), c.network(), c.last4(), "FROZEN",
                        c.spendingLimit(), c.blockedCategories()));
    }

    public Card unfreezeCard(String cardId) {
        return cards.computeIfPresent(cardId, (k, c) ->
                new Card(c.cardId(), c.accountId(), c.type(), c.network(), c.last4(), "ACTIVE",
                        c.spendingLimit(), c.blockedCategories()));
    }

    public Card setSpendingLimit(String cardId, BigDecimal limit) {
        return cards.computeIfPresent(cardId, (k, c) ->
                new Card(c.cardId(), c.accountId(), c.type(), c.network(), c.last4(), c.status(),
                        limit, c.blockedCategories()));
    }

    public Card toggleMerchantCategory(String cardId, String category, boolean blocked) {
        return cards.computeIfPresent(cardId, (k, c) -> {
            var updated = new java.util.HashSet<>(c.blockedCategories());
            if (blocked) updated.add(category.toUpperCase());
            else updated.remove(category.toUpperCase());
            return new Card(c.cardId(), c.accountId(), c.type(), c.network(), c.last4(), c.status(),
                    c.spendingLimit(), Set.copyOf(updated));
        });
    }

    public DisputeCase getCase(String caseId) {
        return cases.get(caseId);
    }

    public DisputeCase createCase(String accountId, String transactionId, String reason) {
        return createCase(accountId, transactionId, reason, false);
    }

    public DisputeCase createCase(String accountId, String transactionId, String reason, boolean fraudFlag) {
        String id = "CASE-" + seq.incrementAndGet();
        var c = new DisputeCase(id, accountId, transactionId, reason, "OPEN", fraudFlag, false, 0);
        cases.put(id, c);
        return c;
    }

    public DisputeCase addEvidence(String caseId) {
        return cases.computeIfPresent(caseId, (k, c) ->
                new DisputeCase(c.caseId(), c.accountId(), c.transactionId(), c.reason(), c.status(),
                        c.fraudFlag(), c.escalated(), c.evidenceCount() + 1));
    }

    public DisputeCase escalateCase(String caseId) {
        return cases.computeIfPresent(caseId, (k, c) ->
                new DisputeCase(c.caseId(), c.accountId(), c.transactionId(), c.reason(), c.status(),
                        c.fraudFlag(), true, c.evidenceCount()));
    }

    public Approval requestApproval(String subject, BigDecimal amount, String requestedBy) {
        String id = "APR-" + seq.incrementAndGet();
        var a = new Approval(id, subject, amount, requestedBy, "PENDING_HUMAN_APPROVAL");
        approvals.put(id, a);
        return a;
    }

    public Map<String, Approval> pendingApprovals() {
        return approvals;
    }

    // --- Ledger -------------------------------------------------------------------------

    public List<LedgerEntry> ledgerFor(String accountId) {
        return ledger.getOrDefault(accountId, new CopyOnWriteArrayList<>());
    }

    /** Posts a real double-entry transfer between two accounts this bank holds — a DEBIT on
        {@code fromAccountId} and a matching CREDIT on {@code toAccountId}, sharing one
        reference, with the account balances updated from the SAME atomic operation that
        posted the entries (never independently — a balance that could drift from its ledger
        is exactly the bug double-entry bookkeeping exists to make impossible).
        @throws IllegalStateException if the source account has insufficient funds. */
    public synchronized List<LedgerEntry> transfer(String fromAccountId, String toAccountId,
                                                    BigDecimal amount, String reference) {
        // Defensive invariant at the domain layer, independent of whatever validation the
        // calling tool did — the balance check below (`compareTo(amount) < 0`) only guards
        // insufficient funds; a non-positive amount passes it trivially (2500 >= -100) and
        // subtract(-100) CREDITS the source while debiting the destination, silently reversing
        // the transfer's direction. The domain layer must never accept a non-positive amount
        // regardless of caller, so this can never regress even if a future caller forgets to check.
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive.");
        }
        Account from = accounts.get(fromAccountId);
        Account to = accounts.get(toAccountId);
        if (from == null || to == null) {
            throw new IllegalStateException("Both accounts must exist to transfer between them.");
        }
        if (from.balance().compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds in " + fromAccountId + ".");
        }

        BigDecimal fromBalance = from.balance().subtract(amount);
        BigDecimal toBalance = to.balance().add(amount);
        accounts.put(fromAccountId, new Account(from.accountId(), from.tenantId(), from.ownerUserId(),
                fromBalance, from.type(), from.nickname()));
        accounts.put(toAccountId, new Account(to.accountId(), to.tenantId(), to.ownerUserId(),
                toBalance, to.type(), to.nickname()));

        Instant now = Instant.now();
        var debit = new LedgerEntry("LDG-" + seq.incrementAndGet(), fromAccountId, amount, "DEBIT",
                fromBalance, reference, now);
        var credit = new LedgerEntry("LDG-" + seq.incrementAndGet(), toAccountId, amount, "CREDIT",
                toBalance, reference, now);
        ledger.computeIfAbsent(fromAccountId, k -> new CopyOnWriteArrayList<>()).add(debit);
        ledger.computeIfAbsent(toAccountId, k -> new CopyOnWriteArrayList<>()).add(credit);

        // The transfer also shows up as ordinary transaction history on both accounts, the
        // same as any other movement a customer would recognize in their statement.
        txns.computeIfAbsent(fromAccountId, k -> new CopyOnWriteArrayList<>())
                .add(new Transaction(debit.entryId(), fromAccountId, LocalDate.now(), amount,
                        "Transfer to " + toAccountId, "DEBIT"));
        txns.computeIfAbsent(toAccountId, k -> new CopyOnWriteArrayList<>())
                .add(new Transaction(credit.entryId(), toAccountId, LocalDate.now(), amount,
                        "Transfer from " + fromAccountId, "CREDIT"));

        return List.of(debit, credit);
    }

    // --- Customer profile (contact info, alert preferences, travel notices) -------------

    public CustomerProfile getProfile(String tenantId, String userId) {
        return profiles.get(tenantId + ":" + userId);
    }

    public CustomerProfile updateContactInfo(String tenantId, String userId, String email, String phone) {
        return profiles.compute(tenantId + ":" + userId, (k, p) -> new CustomerProfile(
                tenantId, userId,
                email != null ? email : (p != null ? p.email() : null),
                phone != null ? phone : (p != null ? p.phone() : null),
                p != null && p.lowBalanceAlerts(), p != null && p.largeTransactionAlerts(),
                p != null ? p.travelNoticeUntil() : null, p != null ? p.travelDestination() : null));
    }

    public CustomerProfile setAlertPreferences(String tenantId, String userId,
                                               boolean lowBalance, boolean largeTransaction) {
        return profiles.compute(tenantId + ":" + userId, (k, p) -> new CustomerProfile(
                tenantId, userId, p != null ? p.email() : null, p != null ? p.phone() : null,
                lowBalance, largeTransaction,
                p != null ? p.travelNoticeUntil() : null, p != null ? p.travelDestination() : null));
    }

    public CustomerProfile setTravelNotice(String tenantId, String userId, LocalDate until, String destination) {
        return profiles.compute(tenantId + ":" + userId, (k, p) -> new CustomerProfile(
                tenantId, userId, p != null ? p.email() : null, p != null ? p.phone() : null,
                p == null || p.lowBalanceAlerts(), p == null || p.largeTransactionAlerts(),
                until, destination));
    }
}
