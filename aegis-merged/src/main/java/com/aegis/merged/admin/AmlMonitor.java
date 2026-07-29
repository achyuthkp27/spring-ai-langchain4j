package com.aegis.merged.admin;

import com.aegis.merged.domain.BankingService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AmlMonitor {

    private static final BigDecimal LARGE_TRANSACTION_THRESHOLD = new BigDecimal("5000");
    private static final int VELOCITY_THRESHOLD = 3; 

    public record Flag(String accountId, String type, String detail, String severity) {
    }

    private final BankingService banking;

    public AmlMonitor(BankingService banking) {
        this.banking = banking;
    }

    public List<Flag> scanAccount(String accountId) {
        List<Flag> flags = new ArrayList<>();
        var txns = banking.getTransactions(accountId);

        for (var t : txns) {
            if (t.amount().compareTo(LARGE_TRANSACTION_THRESHOLD) >= 0) {
                flags.add(new Flag(accountId, "LARGE_TRANSACTION",
                        t.txnId() + ": $" + t.amount() + " at " + t.merchant() + " on " + t.date(),
                        "HIGH"));
            }
        }

        Map<java.time.LocalDate, Long> byDay = txns.stream()
                .collect(Collectors.groupingBy(BankingService.Transaction::date, Collectors.counting()));
        byDay.forEach((day, count) -> {
            if (count > VELOCITY_THRESHOLD) {
                flags.add(new Flag(accountId, "VELOCITY",
                        count + " transactions on " + day + " (threshold " + VELOCITY_THRESHOLD + ")",
                        "MEDIUM"));
            }
        });

        return flags;
    }

    public List<Flag> scanTenant(String tenantId) {
        List<Flag> out = new ArrayList<>();
        for (var account : banking.allAccountsForTenant(tenantId)) {
            out.addAll(scanAccount(account.accountId()));
        }
        return out;
    }
}
