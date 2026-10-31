package com.natsx.smartcafe.ops;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preliminary, single-till cash shift audit. Physical money and waiter handoffs
 * still require in-person verification; this code never settles a payment.
 */
@Service
public class CashDrawerShiftService {
    private static final long MAX_IDR = 1_000_000_000_000L;
    private final JdbcTemplate jdbc;

    public CashDrawerShiftService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Shift(UUID id, String state, UUID openedBy, Instant openedAt,
        long openingFloatIdr, long recordedCashIdr, long expectedDrawerIdr,
        Instant closedAt, UUID closedBy, Long countedCashIdr, Long differenceIdr,
        String closingReason) {}
    public record Dashboard(Shift active, List<Shift> recent) {}
    private record Stored(UUID id, UUID openedBy, Instant openedAt, long opening,
        Instant closedAt, UUID closedBy, Long recorded, Long expected,
        Long counted, Long difference, String reason, String openKey, String closeKey) {}

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        var active = jdbc.query("""
            SELECT * FROM cash_drawer_shifts WHERE closed_at IS NULL LIMIT 1
            """, this::map);
        var closed = jdbc.query("""
            SELECT * FROM cash_drawer_shifts WHERE closed_at IS NOT NULL
            ORDER BY opened_at DESC,id DESC LIMIT 10
            """, this::map);
        return new Dashboard(active.isEmpty() ? null : present(active.getFirst()),
            closed.stream().map(this::present).toList());
    }

    @Transactional
    public Shift open(StaffPrincipal actor, long floatIdr, String key, String requestId) {
        allowed(actor);
        checkMoney(floatIdr);
        checkKey(key);
        // One global till. Serialize open/close commands; the partial unique index
        // independently rejects overlapping opens even across API replicas.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(48725021)", (rs, n) -> 0);
        var repeated = jdbc.query("SELECT * FROM cash_drawer_shifts WHERE open_key=?",
            this::map, key);
        if (!repeated.isEmpty()) {
            var previous = repeated.getFirst();
            if (previous.openedBy().equals(actor.staffId()) && previous.opening() == floatIdr) {
                return present(previous);
            }
            throw conflict("Key already belongs to another shift request");
        }
        Integer current = jdbc.queryForObject(
            "SELECT count(*) FROM cash_drawer_shifts WHERE closed_at IS NULL", Integer.class);
        if (current == null || current != 0) throw conflict("A cash shift is already open");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
            VALUES (?,?,?,?)
            """, id, actor.staffId(), floatIdr, key);
        audit(actor.staffId(), "CASH_SHIFT_OPENED", id, null, requestId,
            floatIdr, null, null);
        return present(load(id, false));
    }

    @Transactional
    public Shift close(UUID id, StaffPrincipal actor, long counted, String key,
                       String reason, String requestId) {
        allowed(actor);
        checkMoney(counted);
        checkKey(key);
        if (reason == null || reason.strip().length() < 5 || reason.strip().length() > 300 ||
            reason.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "A printable reason between 5 and 300 characters is required");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(48725021)", (rs, n) -> 0);
        var old = load(id, true);
        boolean supervisor = actor.roles().contains("MANAGER") || actor.roles().contains("ADMIN");
        if (!supervisor && !old.openedBy().equals(actor.staffId())) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Only the opening cashier or a supervisor may close this shift");
        }
        if (old.closedAt() != null) {
            if (key.equals(old.closeKey()) && counted == old.counted() &&
                reason.strip().equals(old.reason()) && old.closedBy().equals(actor.staffId())) {
                return present(old);
            }
            throw conflict("This shift has already closed");
        }
        Instant cutoff = jdbc.queryForObject("SELECT clock_timestamp()",
            (rs, n) -> rs.getTimestamp(1).toInstant());
        long gross = cashCollected(old.openedAt(), cutoff);
        long expected;
        try { expected = Math.addExact(old.opening(), gross); }
        catch (ArithmeticException e) { throw conflict("Cash total exceeds supported bound"); }
        if (expected > MAX_IDR) throw conflict("Cash total exceeds supported bound");
        long difference = counted - expected;
        int updated = jdbc.update("""
            UPDATE cash_drawer_shifts
            SET closed_at=?,closed_by=?,close_key=?,counted_cash_idr=?,
                recorded_cash_idr=?,expected_drawer_idr=?,difference_idr=?,
                closing_reason=?
            WHERE id=? AND closed_at IS NULL
            """, Timestamp.from(cutoff), actor.staffId(), key, counted,
            gross, expected, difference, reason.strip(), id);
        if (updated != 1) throw conflict("Shift changed during closure");
        audit(actor.staffId(), "CASH_SHIFT_CLOSED", id, reason.strip(), requestId,
            old.opening(), counted, difference);
        return present(load(id, false));
    }

    private long cashCollected(Instant from, Instant to) {
        Long amount = jdbc.queryForObject("""
            SELECT COALESCE(SUM(t.amount_received_idr - t.change_given_idr),0)
            FROM cash_tenders t
            JOIN payment_intents p ON p.id=t.payment_intent_id
            WHERE p.method_selected='CASH' AND t.recorded_at>=? AND t.recorded_at<?
            """, (rs, n) -> rs.getBigDecimal(1).longValueExact(),
            Timestamp.from(from), Timestamp.from(to));
        if (amount == null || amount < 0 || amount > MAX_IDR) {
            throw conflict("Cash collection exceeds supported bound");
        }
        return amount;
    }

    private Stored load(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM cash_drawer_shifts WHERE id=?" +
            (lock ? " FOR UPDATE" : ""), this::map, id);
        if (rows.isEmpty()) throw new ApiFailureException(HttpStatus.NOT_FOUND,
            "NOT_FOUND","Cash shift not found");
        return rows.getFirst();
    }

    private Stored map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new Stored(rs.getObject("id", UUID.class),
            rs.getObject("opened_by", UUID.class), rs.getTimestamp("opened_at").toInstant(),
            rs.getLong("opening_float_idr"), closed == null ? null : closed.toInstant(),
            rs.getObject("closed_by", UUID.class),
            (Long)rs.getObject("recorded_cash_idr"),
            (Long)rs.getObject("expected_drawer_idr"),
            (Long)rs.getObject("counted_cash_idr"),
            (Long)rs.getObject("difference_idr"),
            rs.getString("closing_reason"),rs.getString("open_key"),rs.getString("close_key"));
    }

    private Shift present(Stored s) {
        long gross = s.closedAt() == null ?
            cashCollected(s.openedAt(), Instant.now()) : s.recorded();
        long expected = s.closedAt() == null ? Math.addExact(s.opening(), gross) : s.expected();
        return new Shift(s.id(), s.closedAt() == null ? "OPEN" : "CLOSED",
            s.openedBy(), s.openedAt(), s.opening(), gross, expected,
            s.closedAt(), s.closedBy(), s.counted(), s.difference(), s.reason());
    }

    private void audit(UUID actor, String operation, UUID shiftId, String reason,
                       String requestId, long opening, Long counted, Long difference) {
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,resource_id,
                                   reason,correlation_id,safe_before_after_diff)
            VALUES ('STAFF',? ,?,'CASH_DRAWER_SHIFT',?,?,?,
                    jsonb_build_object('openingFloatIdr',CAST(? AS bigint),
                        'countedCashIdr',CAST(? AS bigint),
                        'differenceIdr',CAST(? AS bigint)))
            """,actor,operation,shiftId,reason,requestId,opening,counted,difference);
    }

    private void allowed(StaffPrincipal actor) {
        if (actor == null || actor.roles().stream().noneMatch(r ->
            r.equals("CASHIER") || r.equals("MANAGER") || r.equals("ADMIN"))) {
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Cash drawer operations require a cashier or supervisor");
        }
    }
    private void checkMoney(long amount) {
        if (amount < 0 || amount > MAX_IDR) throw new ApiFailureException(
            HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid cash amount");
    }
    private void checkKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "A stable idempotency key is required");
        }
    }
    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
