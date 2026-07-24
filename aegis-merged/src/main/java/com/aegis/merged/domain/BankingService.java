package com.aegis.merged.domain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory banking domain stand-in so the assistant has real state to act on.
 * Accounts carry an ownerUserId: the customer-facing bot may only ever touch the
 * authenticated customer's own accounts (enforced inside the tools).
 */
@Service
public class BankingService {

    public record Account(String accountId, String tenantId, String ownerUserId,
                          BigDecimal balance, String type) {
    }

    public record Transaction(String txnId, String accountId, LocalDate date,
                              BigDecimal amount, String merchant) {
    }

    public record Card(String cardId, String accountId, String type, String last4, String status) {
    }

    public record DisputeCase(String caseId, String accountId, String transactionId,
                              String reason, String status) {
    }

    public record Approval(String approvalId, String subject, BigDecimal amount,
                           String requestedBy, String status) {
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> txns = new ConcurrentHashMap<>();
    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final Map<String, DisputeCase> cases = new ConcurrentHashMap<>();
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    public BankingService() {
        accounts.put("ACC-1001", new Account("ACC-1001", "achu-bank", "demo-user", new BigDecimal("2500.00"), "CHECKING"));
        accounts.put("ACC-1002", new Account("ACC-1002", "achu-bank", "demo-user", new BigDecimal("15750.25"), "SAVINGS"));
        accounts.put("ACC-9001", new Account("ACC-9001", "globex-bank", "globex-user", new BigDecimal("99.00"), "CHECKING"));
        txns.put("ACC-1001", List.of(
                new Transaction("TXN-5001", "ACC-1001", LocalDate.now().minusDays(2),
                        new BigDecimal("49.99"), "StreamCo Subscription"),
                new Transaction("TXN-5002", "ACC-1001", LocalDate.now().minusDays(2),
                        new BigDecimal("49.99"), "StreamCo Subscription"),
                new Transaction("TXN-5003", "ACC-1001", LocalDate.now().minusDays(1),
                        new BigDecimal("2000.00"), "Foreign Electronics Ltd")
        ));
        txns.put("ACC-1002", List.of(
                new Transaction("TXN-6001", "ACC-1002", LocalDate.now().minusDays(5),
                        new BigDecimal("29.53"), "Interest Credit"),
                new Transaction("TXN-6002", "ACC-1002", LocalDate.now().minusDays(3),
                        new BigDecimal("400.00"), "ATM Withdrawal - Main St")
        ));
        cards.put("CRD-7001", new Card("CRD-7001", "ACC-1001", "DEBIT", "4412", "ACTIVE"));
        cards.put("CRD-7002", new Card("CRD-7002", "ACC-1001", "CREDIT", "8830", "ACTIVE"));
        cards.put("CRD-7003", new Card("CRD-7003", "ACC-1002", "DEBIT", "1177", "ACTIVE"));
        cards.put("CRD-9001", new Card("CRD-9001", "ACC-9001", "DEBIT", "6021", "ACTIVE"));
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

    public List<Transaction> getTransactions(String accountId) {
        return txns.getOrDefault(accountId, List.of());
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
                new Card(c.cardId(), c.accountId(), c.type(), c.last4(), "FROZEN"));
    }

    public DisputeCase getCase(String caseId) {
        return cases.get(caseId);
    }

    public DisputeCase createCase(String accountId, String transactionId, String reason) {
        String id = "CASE-" + seq.incrementAndGet();
        var c = new DisputeCase(id, accountId, transactionId, reason, "OPEN");
        cases.put(id, c);
        return c;
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
}
