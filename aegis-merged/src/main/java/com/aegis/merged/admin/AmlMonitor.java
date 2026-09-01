package com.aegis.merged.admin;

import com.aegis.merged.domain.BankingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalDate;

@Component
public class AmlMonitor {

    private final BigDecimal largeTransactionThreshold;
    private final int velocityThreshold;

    public record Flag(String accountId, String type, String detail, String severity) {
    }

    private final BankingService banking;

    public AmlMonitor(BankingService banking,
                      @Value("${aegis.aml.large-transaction-threshold:5000}") BigDecimal largeTransactionThreshold,
                      @Value("${aegis.aml.velocity-threshold:3}") int velocityThreshold) {
        this.banking = banking;
        this.largeTransactionThreshold = largeTransactionThreshold;
        this.velocityThreshold = velocityThreshold;
    }

    private static boolean isSelfTransfer(BankingService.Transaction t) {
        return t.merchant() != null
                && (t.merchant().startsWith("Transfer to ") || t.merchant().startsWith("Transfer from "));
    }

    public List<Flag> scanAccount(String accountId) {
        List<Flag> flags = new ArrayList<>();
        var txns = banking.getTransactions(accountId);

        for (var t : txns) {
            if (t.amount().compareTo(largeTransactionThreshold) >= 0) {
                flags.add(new Flag(accountId, "LARGE_TRANSACTION",
                        t.txnId() + ": $" + t.amount() + " at " + t.merchant() + " on " + t.date(),
                        "HIGH"));
            }
        }

        Map<LocalDate, Long> byDay = txns.stream()
                .filter(t -> !isSelfTransfer(t))
                .collect(Collectors.groupingBy(BankingService.Transaction::date, Collectors.counting()));
        byDay.forEach((day, count) -> {
            if (count > velocityThreshold) {
                flags.add(new Flag(accountId, "VELOCITY",
                        count + " transactions on " + day + " (threshold " + velocityThreshold + ")",
                        "MEDIUM"));
            }
        });

        return flags;
    }

    private static final int MAX_FLAGS_RETURNED = 1000;

    public List<Flag> scanTenant(String tenantId) {
        List<Flag> out = new ArrayList<>();
        for (var account : banking.allAccountsForTenant(tenantId)) {
            if (out.size() >= MAX_FLAGS_RETURNED) break;
            out.addAll(scanAccount(account.accountId()));
        }
        return out.size() > MAX_FLAGS_RETURNED ? out.subList(0, MAX_FLAGS_RETURNED) : out;
    }
}
