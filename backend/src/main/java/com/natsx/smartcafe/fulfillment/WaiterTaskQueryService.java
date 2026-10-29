package com.natsx.smartcafe.fulfillment;

import com.natsx.smartcafe.identity.StaffPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A safe, read-only projection of waiter work, scoped to the table's opening waiter.
 * Cashier/manager/admin may supervise all active tasks; kitchen never sees these records.
 */
@Service
public class WaiterTaskQueryService {
    private final JdbcTemplate jdbc;

    public WaiterTaskQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TaskLine(String name, int quantity, String variantLabel, String notes) {}
    public record WaiterTask(UUID orderId, UUID paymentIntentId, String tableCode,
                             String orderStatus, String paymentStatus, long totalIdr,
                             Instant createdAt, List<TaskLine> lines) {}

    private record Base(UUID orderId, UUID paymentIntentId, String tableCode,
                        String orderStatus, String paymentStatus, long totalIdr,
                        Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<WaiterTask> tasks(StaffPrincipal staff) {
        boolean supervisor = staff.roles().stream()
            .anyMatch(role -> role.equals("CASHIER") || role.equals("MANAGER") || role.equals("ADMIN"));
        String sql = """
            SELECT o.id AS order_id,p.id AS payment_intent_id,t.table_code,
                   o.status AS order_status,p.aggregate_status AS payment_status,
                   o.total_idr,o.created_at
            FROM orders o
            JOIN payment_intents p ON p.order_id=o.id
            JOIN table_visits v ON v.id=o.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            WHERE (
              o.status='READY'
              OR (o.status='SERVED' AND p.aggregate_status='AWAITING_CASH')
              OR (o.status='SERVED' AND p.aggregate_status='PAID'
                  AND o.created_at > now() - interval '1 day')
            )
            """ + (supervisor ? "" : " AND v.opened_by=? ") +
            " ORDER BY o.created_at DESC LIMIT 100";
        var rows = jdbc.query(sql, (rs, n) -> new Base(
            rs.getObject("order_id", UUID.class),
            rs.getObject("payment_intent_id", UUID.class),
            rs.getString("table_code"), rs.getString("order_status"),
            rs.getString("payment_status"), rs.getLong("total_idr"),
            rs.getTimestamp("created_at").toInstant()),
            supervisor ? new Object[] {} : new Object[] {staff.staffId()});
        return rows.stream().map(row -> {
            var lines = jdbc.query("""
                SELECT name_snapshot,quantity,variant_snapshot->>'label' AS variant_label,notes
                FROM order_lines WHERE order_id=? ORDER BY name_snapshot
                """, (rs,n) -> new TaskLine(
                rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getString("variant_label"),rs.getString("notes")),row.orderId());
            return new WaiterTask(row.orderId(),row.paymentIntentId(),row.tableCode(),
                row.orderStatus(),row.paymentStatus(),row.totalIdr(),row.createdAt(),lines);
        }).toList();
    }
}
