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
            """,
            com.natsx.smartcafe.common.db.JdbcUuid.parameter(
                staffId, com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)),
            operation, resourceType,
            com.natsx.smartcafe.common.db.JdbcUuid.parameter(
                resourceId, com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc)),
            requestId);
    }
}
