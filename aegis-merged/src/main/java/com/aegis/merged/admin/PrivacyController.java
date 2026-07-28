package com.aegis.merged.admin;

import com.aegis.merged.domain.BankingService;
import com.aegis.merged.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data retention / right-to-erasure (GDPR Art. 17, CCPA deletion rights) — Phase 2 of the
 * production roadmap. Admin-triggered (see SecurityConfig: PERM_admin:all), because a real
 * deployment gates this behind a verified customer request + staff review, not a self-service
 * chat command — erasure is exactly the kind of action that should never be reachable from
 * free-text input.
 *
 * <p><b>The audit-immutability trade-off, made explicit:</b> {@link AuditTrail}'s hash chain
 * (see {@code /api/admin/audit/verify}) exists to prove the audit log hasn't been silently
 * edited. Erasure legitimately DOES edit it — so this redacts rather than deletes audit rows
 * (preserving row counts for compliance reporting), and records the erasure itself as a new,
 * chained audit event, so a chain divergence starting at this action is a documented, logged
 * privacy operation rather than unexplained tampering. A real WORM-backed system would use
 * cryptographic redaction proofs instead of this trade-off; that's real infrastructure work
 * (Phase 2's honest bar), not something this in-memory demo can fully close.
 */
@RestController
@RequestMapping("/api/admin/privacy")
public class PrivacyController {

    private final JdbcTemplate jdbc;
    private final BankingService banking;
    private final AuditTrail audit;

    public PrivacyController(JdbcTemplate jdbc, BankingService banking, AuditTrail audit) {
        this.jdbc = jdbc;
        this.banking = banking;
        this.audit = audit;
    }

    @PostMapping("/erase")
    public Map<String, Object> erase(@RequestParam String tenantId, @RequestParam String userId) {
        // hasAuthority("PERM_admin:all") (SecurityConfig) only proves "an admin of SOME bank" —
        // without this, an achu-bank admin erases globex-bank customers' data on request.
        CurrentUser.requireTenantAccess(tenantId);
        String conversationPrefix = tenantId + ":" + userId + ":";

        int auditRowsRedacted = jdbc.update(
                "UPDATE assistant_audit_event SET question = '[ERASED FOR PRIVACY]' WHERE tenant = ? AND user_id = ?",
                tenantId, userId);
        int widgetRowsDeleted = jdbc.update(
                "DELETE FROM assistant_widget_event WHERE conversation_id LIKE ?", conversationPrefix + "%");
        int memoryRowsDeleted = jdbc.update(
                "DELETE FROM spring_ai_chat_memory WHERE conversation_id LIKE ?", conversationPrefix + "%");
        banking.updateContactInfo(tenantId, userId, "[erased]", "[erased]");

        // Logged as a normal audit event — appended honestly to the SAME hash chain, so it's
        // the permanent, traceable record of why prior rows for this user were redacted.
        audit.record(tenantId, userId, "system", "privacy-erasure", 0, 0,
                "Erasure executed for user " + userId + " (audit=" + auditRowsRedacted
                        + " redacted, widgets=" + widgetRowsDeleted + " deleted, memory=" + memoryRowsDeleted + " deleted)");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("tenantId", tenantId);
        out.put("userId", userId);
        out.put("auditRowsRedacted", auditRowsRedacted);
        out.put("widgetRowsDeleted", widgetRowsDeleted);
        out.put("chatMemoryRowsDeleted", memoryRowsDeleted);
        out.put("note", "Audit rows were redacted, not deleted, to preserve row counts for compliance "
                + "reporting. This will show as a hash-chain divergence at /api/admin/audit/verify from "
                + "this point forward — that divergence is the intended, logged trace of this erasure, "
                + "not evidence of tampering.");
        return out;
    }
}
