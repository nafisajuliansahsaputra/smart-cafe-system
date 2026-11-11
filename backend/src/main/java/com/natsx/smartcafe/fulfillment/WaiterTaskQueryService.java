package com.natsx.smartcafe.fulfillment;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.db.JdbcSqlDialect;
import com.natsx.smartcafe.common.db.JdbcUuid;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff-only task projection. Ordinary waiters see ONLY visits they opened;
 * supervisors may read all active tasks. Order/payment states remain separate.
 */
@Service
public class WaiterTaskQueryService {
    private static final int PAGE_SIZE = 25;
    private static final String TASK_COLUMNS = """
        SELECT o.id AS order_id,p.id AS payment_intent_id,t.table_code,
               o.status AS order_status,p.aggregate_status AS payment_status,
               o.total_idr,o.created_at,cash.amount_received_idr,cash.change_given_idr
        """;
    private static final String TASK_FROM = """
        FROM orders o
        JOIN payment_intents p ON p.order_id=o.id
        JOIN table_visits v ON v.id=o.visit_id
        JOIN cafe_tables t ON t.id=v.table_id
        LEFT JOIN LATERAL (
            SELECT amount_received_idr,change_given_idr FROM cash_tenders
            WHERE payment_intent_id=p.id ORDER BY recorded_at DESC LIMIT 1
        ) cash ON true
        WHERE (
          o.status='READY'
          OR (o.status='SERVED' AND p.aggregate_status='AWAITING_CASH')
          OR (o.status='SERVED' AND p.aggregate_status='PAID'
              AND o.created_at > now() - interval '1 day')
        )
        """;
    // MySQL 8.4 replaces PostgreSQL's interval and LATERAL constructs.
    // Rank tenders by timestamp and UUID for a deterministic latest entry.
    private static final String TASK_FROM_MYSQL = """
        FROM orders o
        JOIN payment_intents p ON p.order_id=o.id
        JOIN table_visits v ON v.id=o.visit_id
        JOIN cafe_tables t ON t.id=v.table_id
        LEFT JOIN (
            SELECT payment_intent_id,amount_received_idr,change_given_idr,
                   ROW_NUMBER() OVER (
                       PARTITION BY payment_intent_id
                       ORDER BY recorded_at DESC,id DESC) AS latest_row
            FROM cash_tenders
        ) cash ON cash.payment_intent_id=p.id AND cash.latest_row=1
        WHERE (
          o.status='READY'
          OR (o.status='SERVED' AND p.aggregate_status='AWAITING_CASH')
          OR (o.status='SERVED' AND p.aggregate_status='PAID'
              AND o.created_at > UTC_TIMESTAMP(6) - INTERVAL 1 DAY)
        )
        """;
    private static final String QUEUE_PRIORITY = """
        ORDER BY CASE
          WHEN o.status='SERVED' AND p.aggregate_status='AWAITING_CASH' THEN 0
          WHEN o.status='READY' THEN 1
          ELSE 2 END,
          o.created_at ASC,o.id ASC
        """;

    private final JdbcTemplate jdbc;

    public WaiterTaskQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record TaskLine(String name, int quantity, String variantLabel, String notes) {}
    public record WaiterTask(UUID orderId, UUID paymentIntentId, String tableCode,
                             String orderStatus, String paymentStatus, long totalIdr,
                             Instant createdAt, Long receivedIdr, Long changeGivenIdr,
                             List<TaskLine> lines) {}
    public record TaskPage(int page, int pageSize, boolean hasNext,
                           long total, List<WaiterTask> items) {}

    private record Base(UUID orderId, UUID paymentIntentId, String tableCode,
                        String orderStatus, String paymentStatus, long totalIdr,
                        Instant createdAt, Long receivedIdr, Long changeGivenIdr) {}
    private record Scope(String where, List<Object> args, JdbcSqlDialect.Vendor vendor) {}

    private Scope scope(StaffPrincipal staff) {
        var vendor = JdbcSqlDialect.detect(jdbc);
        String from = vendor == JdbcSqlDialect.Vendor.MYSQL ? TASK_FROM_MYSQL : TASK_FROM;
        boolean supervisor = staff.roles().stream()
            .anyMatch(role -> role.equals("CASHIER") || role.equals("MANAGER") || role.equals("ADMIN"));
        if (supervisor) return new Scope(from, List.of(), vendor);
        return new Scope(from + " AND v.opened_by=? ",
            List.of(JdbcUuid.parameter(staff.staffId(), vendor)), vendor);
    }

    private List<WaiterTask> load(String sql, List<Object> args, JdbcSqlDialect.Vendor vendor) {
        var rows = jdbc.query(sql, (rs, n) -> new Base(
            JdbcUuid.read(rs, "order_id"),
            JdbcUuid.read(rs, "payment_intent_id"),
            rs.getString("table_code"), rs.getString("order_status"),
            rs.getString("payment_status"), rs.getLong("total_idr"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("amount_received_idr", Long.class),
            rs.getObject("change_given_idr", Long.class)), args.toArray());
        String variantLabel = vendor == JdbcSqlDialect.Vendor.MYSQL
            ? "JSON_UNQUOTE(JSON_EXTRACT(variant_snapshot,'$.label'))"
            : "variant_snapshot->>'label'";
        String linesSql = "SELECT name_snapshot,quantity," + variantLabel
            + " AS variant_label,notes FROM order_lines WHERE order_id=? ORDER BY name_snapshot";
        return rows.stream().map(row -> {
            var lines = jdbc.query(linesSql, (rs,n) -> new TaskLine(
                rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getString("variant_label"),rs.getString("notes")),
                JdbcUuid.parameter(row.orderId(), vendor));
            return new WaiterTask(row.orderId(),row.paymentIntentId(),row.tableCode(),
                row.orderStatus(),row.paymentStatus(),row.totalIdr(),row.createdAt(),
                row.receivedIdr(),row.changeGivenIdr(),lines);
        }).toList();
    }

    // Existing client contract retained. New web UI uses the untruncated page.
    @Transactional(readOnly = true)
    public List<WaiterTask> tasks(StaffPrincipal staff) {
        Scope s = scope(staff);
        return load(TASK_COLUMNS + s.where() + " ORDER BY o.created_at DESC LIMIT 100",
            s.args(), s.vendor());
    }

    /**
     * Outstanding cash collection comes first, then READY food and finally
     * recent settled acknowledgments. Unpaid tasks cannot be hidden by an
     * influx of paid tasks. Stable tie-breaking prevents duplicate rows.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskPage page(StaffPrincipal staff, int page) {
        if (page < 0 || page > 1000) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",
                "Invalid waiter task page");
        }
        Scope s = scope(staff);
        Long total = jdbc.queryForObject("SELECT count(*) " + s.where(),
            Long.class, s.args().toArray());
        var args = new ArrayList<>(s.args());
        args.add(PAGE_SIZE + 1);
        args.add(page * PAGE_SIZE);
        var rows = load(TASK_COLUMNS + s.where() + QUEUE_PRIORITY + " LIMIT ? OFFSET ?",
            args, s.vendor());
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new TaskPage(page, PAGE_SIZE, hasNext, total == null ? 0 : total,
            List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))));
    }
}
