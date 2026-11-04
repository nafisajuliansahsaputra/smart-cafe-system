package com.natsx.smartcafe.receipts;

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
public class GuestReceiptService {
    private final JdbcTemplate jdbc;
    public GuestReceiptService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Line(String name,int quantity,long unitPriceIdr,long lineTotalIdr,String variantLabel) {}
    public record Receipt(UUID id,long number,UUID orderId,long totalIdr,String currency,
                          Instant issuedAt,List<Line> lines) {}
    private record ReceiptMeta(UUID id,long number,UUID orderId,long total,String currency,Instant at) {}

    // Called inside the SAME transaction that settles cash and records audited tender.
    public UUID issueCash(UUID intentId,UUID orderId,long total) {
        UUID receiptId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            SELECT ?,?,?,?,jsonb_build_object(
              'orderId',o.id,'currency','IDR','totalIdr',o.total_idr,
              'method','CASH','lines',(
                SELECT coalesce(jsonb_agg(jsonb_build_object(
                    'name',l.name_snapshot,'quantity',l.quantity,
                    'unitPriceIdr',l.unit_price_idr,'lineTotalIdr',l.line_total_idr,
                    'variant',l.variant_snapshot)
                    ORDER BY l.name_snapshot),'[]'::jsonb)
                FROM order_lines l WHERE l.order_id=o.id))
            FROM orders o WHERE o.id=?
            """,receiptId,intentId,orderId,total,orderId);
        return receiptId;
    }

    /** Lookup by order without exposing receipt UUIDs across guests or before payment. */
    @Transactional(readOnly=true)
    public Receipt ownReceiptForOrder(UUID orderId,GuestPrincipal guest) {
        var receiptIds=jdbc.query("""
            SELECT r.id FROM receipts r
            JOIN orders o ON o.id=r.order_id
            JOIN payment_intents p ON p.order_id=o.id
            WHERE o.id=? AND o.guest_id=? AND o.visit_id=?
              AND p.aggregate_status='PAID'
            """,(rs,n)->rs.getObject("id",UUID.class),
            orderId,guest.guestId(),guest.visitId());
        if(receiptIds.isEmpty())throw new ApiFailureException(
            HttpStatus.NOT_FOUND,"NOT_FOUND","Receipt not found");
        return ownReceipt(receiptIds.getFirst(),guest);
    }

    @Transactional(readOnly=true)
    public Receipt ownReceipt(UUID receiptId,GuestPrincipal guest) {
        var found=jdbc.query("""
            SELECT r.id,r.receipt_number,r.order_id,r.total_idr,r.currency,r.issued_at
            FROM receipts r JOIN orders o ON o.id=r.order_id
            WHERE r.id=? AND o.guest_id=? AND o.visit_id=?
            """,(rs,n)->new ReceiptMeta(rs.getObject("id",UUID.class),rs.getLong("receipt_number"),
                rs.getObject("order_id",UUID.class),rs.getLong("total_idr"),rs.getString("currency"),
                rs.getTimestamp("issued_at").toInstant()),receiptId,guest.guestId(),guest.visitId());
        if(found.isEmpty())throw new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Receipt not found");
        var meta=found.getFirst();
        var lines=jdbc.query("""
            SELECT name_snapshot,quantity,unit_price_idr,line_total_idr,
                   variant_snapshot->>'label' AS variant_label FROM order_lines
            WHERE order_id=? ORDER BY name_snapshot
            """,(rs,n)->new Line(rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getLong("unit_price_idr"),rs.getLong("line_total_idr"),
                rs.getString("variant_label")),meta.orderId());
        return new Receipt(meta.id(),meta.number(),meta.orderId(),meta.total(),meta.currency(),meta.at(),lines);
    }
}
