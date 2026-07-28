package com.aegis.merged.assistant;

/**
 * Derives a customer-facing bank display name from a tenant id (e.g. "achu-bank" ->
 * "Achu Bank") so the assistant can state confidently which bank it's working for instead of
 * hedging ("this appears to be...") — Achu FinBot is one platform serving multiple bank
 * tenants (see BankingService's achu-bank/globex-bank demo accounts), so the identity of
 * "which bank" is tenant-specific even though the assistant's own name is not.
 */
final class TenantNames {

    private TenantNames() {
    }

    static String displayName(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return "your bank";
        String[] parts = tenantId.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
