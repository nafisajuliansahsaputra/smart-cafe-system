package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.identity.StaffPrincipal;
import com.natsx.smartcafe.common.api.ApiFailureException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
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
    private static final int PAGE_SIZE = 25;
    private final JdbcTemplate jdbc;

    public StaffTableQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TableRow(UUID tableId, String tableCode, String publicAlias,
                           int capacity, String tableStatus, boolean activeVisit,
                           UUID manageableVisitId, Instant proofExpiresAt) {}

    public record TablePage(int page, int pageSize, boolean hasNext,
                            long total, List<TableRow> items) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TablePage page(StaffPrincipal staff, int page, String query) {
        if (page < 0 || page > 1000 || query == null || query.length() > 60 ||
            !query.matches("[A-Za-z0-9_-]*")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                "Invalid waiter table search or page");
        }
        boolean supervisor = staff.roles().stream().anyMatch(role ->
            role.equals("CASHIER") || role.equals("MANAGER") || role.equals("ADMIN"));
        String keyword = query.toLowerCase(Locale.ROOT);
        // POSITION matches literal underscores, never SQL LIKE wildcards.
        String predicate = """
            (? = '' OR position(? in lower(t.table_code)) > 0
              OR position(? in lower(t.public_alias)) > 0)
            """;
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM cafe_tables t WHERE " + predicate,
            Long.class, keyword, keyword, keyword);
        List<TableRow> rows = jdbc.query("""
            SELECT t.id,t.table_code,t.public_alias,t.capacity,t.status,
                   v.id AS visit_id,v.opened_by,v.join_proof_expires_at
            FROM cafe_tables t
            LEFT JOIN table_visits v ON v.table_id=t.id AND v.status IN ('ACTIVE','CLOSING')
            WHERE """ + predicate + """
            ORDER BY t.table_code,t.id
            LIMIT ? OFFSET ?
            """, (rs,n) -> {
                UUID visitId = rs.getObject("visit_id", UUID.class);
                UUID openerId = rs.getObject("opened_by", UUID.class);
                boolean canManage = visitId != null
                    && (supervisor || staff.staffId().equals(openerId));
                var expires = canManage ? rs.getTimestamp("join_proof_expires_at") : null;
                return new TableRow(rs.getObject("id", UUID.class),
                    rs.getString("table_code"), rs.getString("public_alias"),
                    rs.getInt("capacity"), rs.getString("status"),
                    visitId != null, canManage ? visitId : null,
                    expires == null ? null : expires.toInstant());
            }, keyword, keyword, keyword, PAGE_SIZE + 1, page * PAGE_SIZE);
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new TablePage(page, PAGE_SIZE, hasNext, count == null ? 0 : count,
            List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))));
    }

    // Kept for older clients; the operational web uses the paginated endpoint.
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
