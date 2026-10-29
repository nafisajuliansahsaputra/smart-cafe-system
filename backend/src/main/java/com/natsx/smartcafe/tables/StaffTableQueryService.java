package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.identity.StaffPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff-only table availability projection. Only an owning waiter or a
 * supervisor may see the active visit ID or proof expiration. Proofs are never
 * returned from this GET endpoint (they are hashed at rest).
 */
@Service
public class StaffTableQueryService {
    private final JdbcTemplate jdbc;

    public StaffTableQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TableRow(UUID tableId, String tableCode, String publicAlias,
                           int capacity, String tableStatus, boolean activeVisit,
                           UUID manageableVisitId, Instant proofExpiresAt) {}

    @Transactional(readOnly = true)
    public List<TableRow> tables(StaffPrincipal staff) {
        boolean supervisor = staff.roles().stream()
            .anyMatch(role -> role.equals("CASHIER") || role.equals("MANAGER") || role.equals("ADMIN"));
        return jdbc.query("""
            SELECT t.id,t.table_code,t.public_alias,t.capacity,t.status,
                   v.id AS visit_id,v.opened_by,v.join_proof_expires_at
            FROM cafe_tables t
            LEFT JOIN table_visits v ON v.table_id=t.id AND v.status IN ('ACTIVE','CLOSING')
            ORDER BY t.table_code,t.id
            LIMIT 250
            """, (rs,n) -> {
                UUID visitId = rs.getObject("visit_id", UUID.class);
                UUID openerId = rs.getObject("opened_by", UUID.class);
                boolean canManage = visitId != null
                    && (supervisor || staff.staffId().equals(openerId));
                var expires = canManage ? rs.getTimestamp("join_proof_expires_at") : null;
                return new TableRow(
                    rs.getObject("id", UUID.class), rs.getString("table_code"),
                    rs.getString("public_alias"), rs.getInt("capacity"),
                    rs.getString("status"), visitId != null,
                    canManage ? visitId : null,
                    expires == null ? null : expires.toInstant());
            });
    }
}
