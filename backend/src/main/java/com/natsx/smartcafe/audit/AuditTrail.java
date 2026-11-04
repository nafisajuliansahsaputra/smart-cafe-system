package com.natsx.smartcafe.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The calling domain transaction owns this insert; no separate auto-commit. */
@Service
public class AuditTrail {
    private final JdbcTemplate jdbc;
    public AuditTrail(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public void staff(UUID staffId,String operation,String resourceType,UUID resourceId,String requestId) {
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,resource_id,correlation_id)
            VALUES ('STAFF',?,?,?,?,?)
            """,staffId,operation,resourceType,resourceId,requestId);
    }
}
