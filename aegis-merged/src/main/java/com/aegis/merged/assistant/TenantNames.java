package com.aegis.merged.assistant;

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
