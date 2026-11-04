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

    // Called in the SAME transaction that settles cash and records tender.
    // The MySQL V5 trigger independently rejects receipts without a matching
    // PAID payment intent; the service also checks the affected row count.
    public UUID issueCash(UUID intentId, UUID orderId, long total) {
        UUID receiptId = UUID.randomUUID();
        var vendor = com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        if (vendor == com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.POSTGRESQL) {
            int inserted = jdbc.update("""
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
                """, dbUuid(receiptId), dbUuid(intentId), dbUuid(orderId), total,
                dbUuid(orderId));
            if (inserted != 1) throw new IllegalStateException("Receipt order is not available");
            return receiptId;
        }

        // MySQL-native JSON keeps nested variant objects typed, rather than
        // string-escaping financial snapshots or serializing JSON by hand.
        // The V5 PAID matching-payment trigger is still the final authority.
        int inserted = jdbc.update("""
            INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
            SELECT ?,?,?,?,
                JSON_OBJECT(
                    'orderId',o.id,'currency','IDR','totalIdr',o.total_idr,
                    'method','CASH','lines',
                    COALESCE((
                        SELECT JSON_ARRAYAGG(JSON_OBJECT(
                            'name',l.name_snapshot,'quantity',l.quantity,
                            'unitPriceIdr',l.unit_price_idr,'lineTotalIdr',l.line_total_idr,
                            'variant',l.variant_snapshot))
                        FROM order_lines l WHERE l.order_id=o.id
                    ),JSON_ARRAY())
                )
            FROM orders o JOIN payment_intents pay ON pay.order_id=o.id
            WHERE o.id=? AND pay.id=? AND pay.method_selected='CASH'
              AND pay.aggregate_status='PAID'
              AND pay.amount_paid_idr=? AND pay.amount_due_idr=?
              AND o.total_idr=?
            """, dbUuid(receiptId), dbUuid(intentId), dbUuid(orderId), total,
            dbUuid(orderId), dbUuid(intentId), total, total, total);
        if (inserted != 1) {
            throw new IllegalStateException("Receipt requires a matching settled cash payment");
        }
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
            """,(rs,n)->com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),
            dbUuid(orderId),dbUuid(guest.guestId()),dbUuid(guest.visitId()));
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
            """,(rs,n)->new ReceiptMeta(
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"id"),
                rs.getLong("receipt_number"),
                com.natsx.smartcafe.common.db.JdbcUuid.read(rs,"order_id"),
                rs.getLong("total_idr"),rs.getString("currency"),
                com.natsx.smartcafe.common.db.JdbcUtcTime.toInstant(rs.getObject("issued_at"))),
                dbUuid(receiptId),dbUuid(guest.guestId()),dbUuid(guest.visitId()));
        if(found.isEmpty())throw new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Receipt not found");
        var meta=found.getFirst();
        var vendor = com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        String variantLabel = switch (vendor) {
            case POSTGRESQL -> "variant_snapshot->>'label'";
            case MYSQL -> "JSON_UNQUOTE(JSON_EXTRACT(variant_snapshot,'$.label'))";
        };
        String sql = "SELECT name_snapshot,quantity,unit_price_idr,line_total_idr,"
            + variantLabel + " AS variant_label FROM order_lines"
            + " WHERE order_id=? ORDER BY name_snapshot";
        var lines=jdbc.query(sql,(rs,n)->new Line(
                rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getLong("unit_price_idr"),rs.getLong("line_total_idr"),
                rs.getString("variant_label")),dbUuid(meta.orderId()));
        return new Receipt(meta.id(),meta.number(),meta.orderId(),meta.total(),meta.currency(),meta.at(),lines);
    }
    private Object dbUuid(UUID value) {
        return com.natsx.smartcafe.common.db.JdbcUuid.parameter(
            value, com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc));
    }
}
