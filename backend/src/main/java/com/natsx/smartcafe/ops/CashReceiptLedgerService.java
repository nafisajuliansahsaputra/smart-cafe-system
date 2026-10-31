package com.natsx.smartcafe.ops;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, bounded detail projection of issued and fully paid CASH receipts. */
@Service
public class CashReceiptLedgerService {
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final int PAGE_SIZE = 20;
    private final JdbcTemplate jdbc;

    public CashReceiptLedgerService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CashReceiptItem(UUID receiptId, long receiptNumber, UUID orderId,
                                  String tableCode, long totalIdr, Instant issuedAt) {}
    public record CashReceiptPage(String date, String timezone, String currency,
                                  int page, boolean hasNext, List<CashReceiptItem> items) {}

    @Transactional(readOnly = true)
    public CashReceiptPage byDay(String dateParam, String pageParam) {
        if (dateParam == null || !dateParam.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw invalid("A valid WIB report date is required");
        }
        LocalDate day;
        try { day = LocalDate.parse(dateParam); }
        catch (DateTimeParseException ex) { throw invalid("Invalid report date"); }
        if (day.getYear() < 2000 || day.getYear() > 2100) throw invalid("Invalid report date");
        if (pageParam == null || !pageParam.matches("(0|[1-9][0-9]{0,3})")) {
            throw invalid("Invalid page number");
        }
        int page = Integer.parseInt(pageParam);
        if (page > 1000) throw invalid("Page exceeds maximum");

        Timestamp start = Timestamp.from(day.atStartOfDay(WIB).toInstant());
        Timestamp end = Timestamp.from(day.plusDays(1).atStartOfDay(WIB).toInstant());
        var rows = jdbc.query("""
            SELECT r.id AS receipt_id, r.receipt_number, r.order_id,
                   t.table_code, r.total_idr, r.issued_at
            FROM receipts r
            JOIN payment_intents p ON p.id = r.payment_intent_id
            JOIN orders o ON o.id = r.order_id
            JOIN table_visits v ON v.id = o.visit_id
            JOIN cafe_tables t ON t.id = v.table_id
            WHERE p.method_selected = 'CASH'
              AND p.aggregate_status = 'PAID'
              AND p.amount_paid_idr = r.total_idr
              AND r.issued_at >= ? AND r.issued_at < ?
            ORDER BY r.issued_at DESC, r.id DESC
            LIMIT 21 OFFSET ?
            """, (rs, n) -> new CashReceiptItem(
                rs.getObject("receipt_id", UUID.class),
                rs.getLong("receipt_number"),
                rs.getObject("order_id", UUID.class),
                rs.getString("table_code"),
                rs.getLong("total_idr"),
                rs.getTimestamp("issued_at").toInstant()),
            start, end, page * PAGE_SIZE);
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new CashReceiptPage(day.toString(), WIB.getId(), "IDR",
            page, hasNext, hasNext ? List.copyOf(rows.subList(0, PAGE_SIZE)) : rows);
    }

    private ApiFailureException invalid(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
