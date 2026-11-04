package com.natsx.smartcafe.ops;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gross cash receipts for a WIB day, not a refund-adjusted profit statement. */
@Service
public class DailyCashReportService {
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private final JdbcTemplate jdbc;

    public DailyCashReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record SoldItem(String name, long quantity, long grossIdr) {}
    public record DailyCashReport(String date, String timezone, String currency,
                                  long paidOrders, long grossCollectedIdr,
                                  List<SoldItem> topItems) {}

    @Transactional(readOnly = true)
    public DailyCashReport daily(String dateParam) {
        LocalDate day;
        if (dateParam == null) {
            day = LocalDate.now(WIB);
        } else {
            if (!dateParam.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalidDate();
            try { day = LocalDate.parse(dateParam); }
            catch (DateTimeParseException ex) { throw invalidDate(); }
        }
        if (day.getYear() < 2000 || day.getYear() > 2100) throw invalidDate();
        Timestamp start = Timestamp.from(day.atStartOfDay(WIB).toInstant());
        Timestamp end = Timestamp.from(day.plusDays(1).atStartOfDay(WIB).toInstant());
        var totals = jdbc.queryForObject("""
            SELECT COUNT(*) AS paid_orders, COALESCE(SUM(r.total_idr),0) AS gross_idr
            FROM receipts r
            JOIN payment_intents p ON p.id=r.payment_intent_id
            WHERE p.method_selected='CASH'
              AND r.issued_at>=? AND r.issued_at<?
            """, (rs,n) -> new long[] {
                rs.getLong("paid_orders"),
                rs.getBigDecimal("gross_idr").longValueExact()
            }, start, end);
        var topItems = jdbc.query("""
            SELECT l.name_snapshot AS item_name,
                   SUM(l.quantity) AS sold_quantity,
                   SUM(l.line_total_idr) AS gross_idr
            FROM receipts r
            JOIN payment_intents p ON p.id=r.payment_intent_id
            JOIN order_lines l ON l.order_id=r.order_id
            WHERE p.method_selected='CASH'
              AND r.issued_at>=? AND r.issued_at<?
            GROUP BY l.name_snapshot
            ORDER BY sold_quantity DESC, item_name ASC
            LIMIT 10
            """, (rs,n) -> new SoldItem(
                rs.getString("item_name"),
                rs.getBigDecimal("sold_quantity").longValueExact(),
                rs.getBigDecimal("gross_idr").longValueExact()
            ), start, end);
        return new DailyCashReport(day.toString(), WIB.getId(), "IDR",
            totals[0], totals[1], topItems);
    }

    private ApiFailureException invalidDate() {
        return new ApiFailureException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "Date must be a valid YYYY-MM-DD between 2000 and 2100");
    }
}
