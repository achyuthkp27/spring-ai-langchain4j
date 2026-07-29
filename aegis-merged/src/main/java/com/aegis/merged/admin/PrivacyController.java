package com.aegis.merged.admin;

import com.aegis.merged.domain.BankingService;
import com.aegis.merged.rag.SemanticCache;
import com.aegis.merged.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/privacy")
public class PrivacyController {

    private final JdbcTemplate jdbc;
    private final BankingService banking;
    private final AuditTrail audit;
    private final SemanticCache semanticCache;

    public PrivacyController(JdbcTemplate jdbc, BankingService banking, AuditTrail audit,
                             SemanticCache semanticCache) {
        this.jdbc = jdbc;
        this.banking = banking;
        this.audit = audit;
        this.semanticCache = semanticCache;
    }

    @PostMapping("/erase")
    public Map<String, Object> erase(@RequestParam String tenantId, @RequestParam String userId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank.");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank.");
        }

        CurrentUser.requireTenantAccess(tenantId);
        String conversationPrefix = tenantId + ":" + userId + ":";

        int auditRowsRedacted = jdbc.update(
                "UPDATE assistant_audit_event SET question = '[ERASED FOR PRIVACY]' WHERE tenant = ? AND user_id = ?",
                tenantId, userId);
        int widgetRowsDeleted = jdbc.update(
                "DELETE FROM assistant_widget_event WHERE conversation_id LIKE ?", conversationPrefix + "%");
        int memoryRowsDeleted = jdbc.update(
                "DELETE FROM spring_ai_chat_memory WHERE conversation_id LIKE ?", conversationPrefix + "%");
        banking.eraseContactInfoIfPresent(tenantId, userId, "[erased]", "[erased]");
        semanticCache.clear(tenantId);

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
