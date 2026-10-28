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
    public record OrderLine(String name,int quantity,long unitPriceIdr,long lineTotalIdr,String notes) {}
    public record OrderDetail(UUID id,String tableCode,String orderStatus,String paymentStatus,
                              long subtotalIdr,long taxIdr,long serviceFeeIdr,long totalIdr,
                              String paymentMethod,Instant createdAt,List<OrderLine> lines) {}

    @Transactional(readOnly = true)
    public List<QueueItem> search(String orderStatus,String paymentStatus,String tableCode,UUID orderId) {
        StringBuilder query=new StringBuilder("""
            SELECT o.id,t.table_code,o.status,p.aggregate_status,o.total_idr,o.created_at FROM orders o
            JOIN table_visits v ON v.id=o.visit_id JOIN cafe_tables t ON t.id=v.table_id
            JOIN payment_intents p ON p.order_id=o.id WHERE 1=1
            """);
        var arguments=new ArrayList<Object>();
        if(orderStatus!=null && !orderStatus.isBlank()) {
            try { OrderStatus.valueOf(orderStatus); }
            catch(IllegalArgumentException ex) { throw invalid("Invalid order status"); }
            query.append(" AND o.status=?");
            arguments.add(orderStatus);
        }
        if(paymentStatus!=null && !paymentStatus.isBlank()) {
            try { PaymentStatus.valueOf(paymentStatus); }
            catch(IllegalArgumentException ex) { throw invalid("Invalid payment status"); }
            query.append(" AND p.aggregate_status=?");
            arguments.add(paymentStatus);
        }
        if(tableCode!=null && !tableCode.isBlank()) {
            if(tableCode.length()>40)throw invalid("Invalid table code");
            query.append(" AND t.table_code=?");
            arguments.add(tableCode);
        }
        if(orderId!=null) {
            query.append(" AND o.id=?");
            arguments.add(orderId);
        }
        query.append(" ORDER BY o.created_at DESC LIMIT 100");
        return jdbc.query(query.toString(),(rs,n)->new QueueItem(rs.getObject("id",UUID.class),
            rs.getString("table_code"),rs.getString("status"),rs.getString("aggregate_status"),
            rs.getLong("total_idr"),rs.getTimestamp("created_at").toInstant()),arguments.toArray());
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(UUID id) {
        var details=jdbc.query("""
            SELECT o.id,t.table_code,o.status,p.aggregate_status,o.subtotal_idr,o.tax_idr,
                   o.service_fee_idr,o.total_idr,p.method_selected,o.created_at
            FROM orders o JOIN table_visits v ON v.id=o.visit_id
            JOIN cafe_tables t ON t.id=v.table_id
            JOIN payment_intents p ON p.order_id=o.id WHERE o.id=?
            """,(rs,n)->new Base(rs.getObject("id",UUID.class),rs.getString("table_code"),
                rs.getString("status"),rs.getString("aggregate_status"),rs.getLong("subtotal_idr"),
                rs.getLong("tax_idr"),rs.getLong("service_fee_idr"),rs.getLong("total_idr"),
                rs.getString("method_selected"),rs.getTimestamp("created_at").toInstant()),id);
        if(details.isEmpty())throw new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND",
            "Order not found");
        var order=details.getFirst();
        var lines=jdbc.query("""
            SELECT name_snapshot,quantity,unit_price_idr,line_total_idr,notes
            FROM order_lines WHERE order_id=? ORDER BY name_snapshot
            """,(rs,n)->new OrderLine(rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getLong("unit_price_idr"),rs.getLong("line_total_idr"),rs.getString("notes")),id);
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
