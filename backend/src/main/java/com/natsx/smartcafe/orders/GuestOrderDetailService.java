package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestOrderDetailService {
    private final JdbcTemplate jdbc;
    public GuestOrderDetailService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Line(String name,int quantity,long unitPriceIdr,long lineTotalIdr,String notes) {}
    public record Detail(UUID id,String status,String paymentStatus,long totalIdr,
                         Instant createdAt,List<Line> lines) {}
    private record Order(UUID id,String status,String paymentStatus,long total,Instant createdAt) {}

    @Transactional(readOnly=true)
    public Detail ownOrder(GuestPrincipal guest,UUID orderId) {
        var found=jdbc.query("""
            SELECT o.id,o.status,p.aggregate_status,o.total_idr,o.created_at
            FROM orders o JOIN payment_intents p ON p.order_id=o.id
            WHERE o.id=? AND o.guest_id=? AND o.visit_id=?
            """,(rs,n)->new Order(rs.getObject("id",UUID.class),rs.getString("status"),
                rs.getString("aggregate_status"),rs.getLong("total_idr"),
                rs.getTimestamp("created_at").toInstant()),
            orderId,guest.guestId(),guest.visitId());
        if(found.isEmpty())throw new ApiFailureException(
            HttpStatus.NOT_FOUND,"NOT_FOUND","Order not found");
        var data=found.getFirst();
        var lines=jdbc.query("""
            SELECT name_snapshot,quantity,unit_price_idr,line_total_idr,notes
            FROM order_lines WHERE order_id=? ORDER BY name_snapshot
            """,(rs,n)->new Line(rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getLong("unit_price_idr"),rs.getLong("line_total_idr"),
                rs.getString("notes")),orderId);
        return new Detail(data.id(),data.status(),data.paymentStatus(),data.total(),
            data.createdAt(),lines);
    }
}
