package com.natsx.smartcafe.ops;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.orders.OrderStatus;
import com.natsx.smartcafe.payments.PaymentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only projections; no duplicate state owner or financial mutation. */
@Service
public class OrderControlQueryService {
    private final JdbcTemplate jdbc;
    public OrderControlQueryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record QueueItem(UUID id,String tableCode,String orderStatus,String paymentStatus,
                            long totalIdr,Instant createdAt) {}
    public record OrderLine(String name,int quantity,long unitPriceIdr,long lineTotalIdr,
                            String notes,String variantLabel) {}
    public record OrderDetail(UUID id,String tableCode,String orderStatus,String paymentStatus,
                              long subtotalIdr,long taxIdr,long serviceFeeIdr,long totalIdr,
                              String paymentMethod,Instant createdAt,List<OrderLine> lines) {}

    private static final int PAGE_SIZE = 25;
    private static final String QUEUE_COLUMNS = """
        SELECT o.id,t.table_code,o.status,p.aggregate_status,o.total_idr,o.created_at
        """;
    private static final String QUEUE_FROM = """
        FROM orders o
        JOIN table_visits v ON v.id=o.visit_id JOIN cafe_tables t ON t.id=v.table_id
        JOIN payment_intents p ON p.order_id=o.id WHERE 1=1
        """;

    public record QueuePage(int page, int pageSize, boolean hasNext,
                            long total, List<QueueItem> items) {}
    private record Filters(String fromWhere, List<Object> arguments) {}

    private Filters filters(String orderStatus, String paymentStatus, String tableCode, UUID orderId) {
        StringBuilder fromWhere = new StringBuilder(QUEUE_FROM);
        var arguments = new ArrayList<Object>();
        if (orderStatus != null && !orderStatus.isBlank()) {
            try { OrderStatus.valueOf(orderStatus); }
            catch (IllegalArgumentException ex) { throw invalid("Invalid order status"); }
            fromWhere.append(" AND o.status=?");
            arguments.add(orderStatus);
        }
        if (paymentStatus != null && !paymentStatus.isBlank()) {
            try { PaymentStatus.valueOf(paymentStatus); }
            catch (IllegalArgumentException ex) { throw invalid("Invalid payment status"); }
            fromWhere.append(" AND p.aggregate_status=?");
            arguments.add(paymentStatus);
        }
        if (tableCode != null && !tableCode.isBlank()) {
            if (tableCode.length() > 40) throw invalid("Invalid table code");
            fromWhere.append(" AND t.table_code=?");
            arguments.add(tableCode);
        }
        if (orderId != null) {
            fromWhere.append(" AND o.id=?");
            arguments.add(orderId);
        }
        return new Filters(fromWhere.toString(), arguments);
    }

    private List<QueueItem> readQueue(String sql, List<Object> arguments) {
        return jdbc.query(sql, (rs,n) -> new QueueItem(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
            rs.getString("table_code"), rs.getString("status"),
            rs.getString("aggregate_status"), rs.getLong("total_idr"),
            rs.getTimestamp("created_at").toInstant()), arguments.toArray());
    }

    // Legacy callers retain the existing 100-order response contract.
    @Transactional(readOnly = true)
    public List<QueueItem> search(String orderStatus, String paymentStatus, String tableCode, UUID orderId) {
        Filters f = filters(orderStatus, paymentStatus, tableCode, orderId);
        return readQueue(QUEUE_COLUMNS + f.fromWhere() +
            " ORDER BY o.created_at DESC LIMIT 100", f.arguments());
    }

    // The actual OCC browser uses this PostgreSQL-backed page, not the
    // historical capped queue. Count and items share a consistent snapshot.
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public QueuePage page(int page, String orderStatus, String paymentStatus,
                          String tableCode, UUID orderId) {
        if (page < 0 || page > 1000) throw invalid("Invalid OCC page");
        Filters f = filters(orderStatus, paymentStatus, tableCode, orderId);
        Long total = jdbc.queryForObject("SELECT count(*) " + f.fromWhere(),
            Long.class, f.arguments().toArray());
        var arguments = new ArrayList<>(f.arguments());
        arguments.add(PAGE_SIZE + 1);
        arguments.add(page * PAGE_SIZE);
        List<QueueItem> rows = readQueue(QUEUE_COLUMNS + f.fromWhere() +
            " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?", arguments);
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new QueuePage(page, PAGE_SIZE, hasNext, total == null ? 0 : total,
            List.copyOf(rows.subList(0, Math.min(rows.size(), PAGE_SIZE))));
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(UUID id) {
        var details=jdbc.query("""
            SELECT o.id,t.table_code,o.status,p.aggregate_status,o.subtotal_idr,o.tax_idr,
                   o.service_fee_idr,o.total_idr,p.method_selected,o.created_at
            FROM orders o JOIN table_visits v ON v.id=o.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            JOIN payment_intents p ON p.order_id=o.id WHERE o.id=?
            """,(rs,n)->new Base(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),rs.getString("table_code"),
                rs.getString("status"),rs.getString("aggregate_status"),rs.getLong("subtotal_idr"),
                rs.getLong("tax_idr"),rs.getLong("service_fee_idr"),rs.getLong("total_idr"),
                rs.getString("method_selected"),rs.getTimestamp("created_at").toInstant()),id);
        if(details.isEmpty())throw new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND",
            "Order not found");
        var order=details.getFirst();
        var lines=jdbc.query("""
            SELECT name_snapshot,quantity,unit_price_idr,line_total_idr,notes,
                   variant_snapshot->>'label' AS variant_label
            FROM order_lines WHERE order_id=? ORDER BY name_snapshot
            """,(rs,n)->new OrderLine(rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getLong("unit_price_idr"),rs.getLong("line_total_idr"),
                rs.getString("notes"),rs.getString("variant_label")),id);
        return new OrderDetail(order.id(),order.tableCode(),order.orderStatus(),order.paymentStatus(),
            order.subtotal(),order.tax(),order.fee(),order.total(),order.method(),
            order.createdAt(),lines);
    }

    private record Base(UUID id,String tableCode,String orderStatus,String paymentStatus,
                        long subtotal,long tax,long fee,long total,String method,Instant createdAt) {}

    private ApiFailureException invalid(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);
    }
}
