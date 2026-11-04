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
    public record PendingHandoff(UUID tenderId, UUID waiterStaffId, String waiterLogin,
        String tableCode, long netCashIdr, Instant collectedAt) {}
    public record Dashboard(Shift active, List<Shift> recent,
        long pendingHandoffCount, List<PendingHandoff> pendingHandoffs) {}
    public record ReceivedHandoff(UUID tenderId, UUID shiftId, long netCashIdr,
        String state) {}
    private record HandoffRow(UUID shiftId, UUID waiterId, UUID receivedBy,
        Instant receivedAt, String key, long net) {}
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
        if (active.isEmpty()) {
            return new Dashboard(null, closed.stream().map(this::present).toList(),
                0, List.of());
        }
        UUID shiftId = active.getFirst().id();
        Long total = jdbc.queryForObject("""
            SELECT count(*) FROM cash_tender_handoffs
            WHERE cash_shift_id=? AND received_at IS NULL
            """,Long.class,shiftId);
        var pending = jdbc.query("""
            SELECT h.tender_id,h.waiter_staff_id,u.login,t.amount_received_idr,
                   t.change_given_idr,t.recorded_at,ct.table_code
            FROM cash_tender_handoffs h
            JOIN cash_tenders t ON t.id=h.tender_id
            JOIN staff_users u ON u.id=h.waiter_staff_id
            JOIN payment_intents p ON p.id=t.payment_intent_id
            JOIN orders o ON o.id=p.order_id
            JOIN table_visits v ON v.id=o.visit_id
            JOIN cafe_tables ct ON ct.id=v.table_id
            WHERE h.cash_shift_id=? AND h.received_at IS NULL
            ORDER BY t.recorded_at,h.tender_id LIMIT 50
            """,(rs,n)->new PendingHandoff(
                rs.getObject("tender_id",UUID.class),
                rs.getObject("waiter_staff_id",UUID.class),
                rs.getString("login"),rs.getString("table_code"),
                Math.subtractExact(rs.getLong("amount_received_idr"),
                    rs.getLong("change_given_idr")),
                rs.getTimestamp("recorded_at").toInstant()),shiftId);
        return new Dashboard(present(active.getFirst()),
            closed.stream().map(this::present).toList(),total == null ? 0 : total,pending);
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
        Long pending = jdbc.queryForObject("""
            SELECT count(*) FROM cash_tender_handoffs
            WHERE cash_shift_id=? AND received_at IS NULL
            """,Long.class,id);
        if (pending == null || pending > 0) {
            throw conflict("Unconfirmed waiter cash handoffs must be received before closing");
        }
        Instant cutoff = jdbc.queryForObject("SELECT clock_timestamp()",
            (rs, n) -> rs.getTimestamp(1).toInstant());
        long gross = cashCollected(id);
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

    private long cashCollected(UUID shiftId) {
        Long amount = jdbc.queryForObject("""
            SELECT COALESCE(SUM(t.amount_received_idr - t.change_given_idr),0)
            FROM cash_tenders t
            JOIN payment_intents p ON p.id=t.payment_intent_id
            WHERE p.method_selected='CASH' AND t.cash_shift_id=?
            """, (rs, n) -> rs.getBigDecimal(1).longValueExact(),shiftId);
        if (amount == null || amount < 0 || amount > MAX_IDR) {
            throw conflict("Cash collection exceeds supported bound");
        }
        return amount;
    }

    /** Cashier physically verifies the exact net amount and signs custody transfer. */
    @Transactional
    public ReceivedHandoff receive(UUID shiftId, UUID tenderId, StaffPrincipal actor,
                                   long counted, String key, String requestId) {
        allowed(actor);
        checkMoney(counted);
        checkKey(key);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(48725021)",(rs,n)->0);
        var shift = load(shiftId,true);
        var rows = jdbc.query("""
            SELECT h.cash_shift_id,h.waiter_staff_id,h.received_by,h.received_at,
                   h.acknowledgment_key,t.amount_received_idr,t.change_given_idr
            FROM cash_tender_handoffs h
            JOIN cash_tenders t ON t.id=h.tender_id
            WHERE h.tender_id=? AND h.cash_shift_id=? FOR UPDATE OF h
            """,(rs,n)->new HandoffRow(rs.getObject("cash_shift_id",UUID.class),
                rs.getObject("waiter_staff_id",UUID.class),
                rs.getObject("received_by",UUID.class),
                rs.getTimestamp("received_at")==null?null:
                    rs.getTimestamp("received_at").toInstant(),
                rs.getString("acknowledgment_key"),
                Math.subtractExact(rs.getLong("amount_received_idr"),
                    rs.getLong("change_given_idr"))),tenderId,shiftId);
        if (rows.isEmpty()) throw new ApiFailureException(HttpStatus.NOT_FOUND,
            "NOT_FOUND","Cash handoff is not available in this shift");
        var handoff=rows.getFirst();
        if (handoff.receivedAt()!=null) {
            if (key.equals(handoff.key()) && handoff.receivedBy().equals(actor.staffId())
                && counted==handoff.net()) {
                return new ReceivedHandoff(tenderId,shiftId,handoff.net(),"RECEIVED");
            }
            throw conflict("Cash handoff already confirmed");
        }
        if (shift.closedAt()!=null) throw conflict("Cannot receive cash into a closed shift");
        if (counted!=handoff.net()) {
            throw conflict("Counted cash must match recorded net amount; investigate before accepting");
        }
        int changed=jdbc.update("""
            UPDATE cash_tender_handoffs
            SET received_by=?,received_at=clock_timestamp(),acknowledgment_key=?
            WHERE tender_id=? AND cash_shift_id=? AND received_at IS NULL
            """,actor.staffId(),key,tenderId,shiftId);
        if (changed!=1) throw conflict("Cash handoff was changed");
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,
                resource_id,correlation_id,safe_before_after_diff)
            VALUES ('STAFF',?,'CASH_HANDOFF_RECEIVED','CASH_TENDER',?,?,
                jsonb_build_object('shiftId',CAST(? AS text),'netCashIdr',CAST(? AS bigint)))
            """,actor.staffId(),tenderId,requestId,shiftId.toString(),handoff.net());
        return new ReceivedHandoff(tenderId,shiftId,handoff.net(),"RECEIVED");
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
            cashCollected(s.id()) : s.recorded();
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
