package com.natsx.smartcafe.common.events;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-identity durable cursors. Never expose the global cursor through a
 * public/anonymous endpoint: guests see only their own order events.
 */
@Service
public class LiveCursorService {
    private static final long LONG_POLL_MS = 14000;
    private static final long RECOVERY_CHECK_MS = 2500;
    private final LivePollCapacity admission;
    private final JdbcTemplate jdbc;
    private final LiveChangeWakeup wakeup;

    public LiveCursorService(JdbcTemplate jdbc, LiveChangeWakeup wakeup,
                             LivePollCapacity admission) {
        this.jdbc = jdbc;
        this.wakeup = wakeup;
        this.admission = admission;
    }

    public record Cursor(long cursor) { }

    public Cursor guest(String topic, UUID orderId, Long after, GuestPrincipal guest,
                        Supplier<Boolean> stillAuthorized) throws InterruptedException {
        if ("games".equals(topic)) {
            if (orderId != null) badTopic();
            return await(after, stillAuthorized, guest.guestId(), LivePollCapacity.Kind.GUEST,
                () -> cursor("""
                    SELECT COALESCE(MAX(s.sequence_id),0) FROM live_game_signals s
                    JOIN physical_game_loans l ON l.id=s.loan_id
                    WHERE l.guest_id=? AND l.visit_id=?
                    """,guest.guestId(),guest.visitId()));
        }
        if (!"orders".equals(topic) && !"order".equals(topic)) badTopic();
        if (("order".equals(topic)) != (orderId != null)) badTopic();
        if ("order".equals(topic)) {
            Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM orders
                    WHERE id=? AND guest_id=? AND visit_id=?)
                """, Boolean.class, orderId,guest.guestId(),guest.visitId());
            if (!Boolean.TRUE.equals(owned)) throw missing();
        }
        return await(after, stillAuthorized, guest.guestId(), LivePollCapacity.Kind.GUEST, () -> {
            String sql = """
                SELECT COALESCE(MAX(l.sequence_id),0)
                FROM live_order_signals l JOIN orders o ON o.id=l.order_id
                WHERE o.guest_id=? AND o.visit_id=?
                """;
            if (orderId != null) {
                return cursor(sql + " AND o.id=?",guest.guestId(),guest.visitId(),orderId);
            }
            return cursor(sql,guest.guestId(),guest.visitId());
        });
    }

    public Cursor staff(String scope, String topic, UUID orderId, Long after,
                        StaffPrincipal staff, Supplier<Boolean> stillAuthorized)
                        throws InterruptedException {
        if ("ops".equals(scope)) {
            if (!staff.roles().stream().anyMatch(r -> r.equals("CASHIER") ||
                r.equals("MANAGER") || r.equals("ADMIN"))) throw forbidden();
            if (!"orders".equals(topic) && !"order".equals(topic)) badTopic();
            if (("order".equals(topic)) != (orderId != null)) badTopic();
            if (orderId != null && !existsOrder(orderId)) throw missing();
            return await(after, stillAuthorized, staff.staffId(), LivePollCapacity.Kind.STAFF, () -> orderId == null
                ? cursor("SELECT COALESCE(MAX(sequence_id),0) FROM live_order_signals")
                : cursor("SELECT COALESCE(MAX(sequence_id),0) FROM live_order_signals WHERE order_id=?",orderId));
        }
        if ("kitchen".equals(scope)) {
            if (!staff.roles().stream().anyMatch(r -> r.equals("KITCHEN") ||
                r.equals("MANAGER") || r.equals("ADMIN"))) throw forbidden();
            if (!"kitchen".equals(topic) || orderId != null) badTopic();
            return await(after, stillAuthorized, staff.staffId(), LivePollCapacity.Kind.STAFF, () -> cursor("""
                SELECT COALESCE(MAX(sequence_id),0) FROM live_order_signals
                WHERE event_type IN ('ORDER_ACCEPTED','ORDER_PREPARING',
                                     'ORDER_READY','ORDER_SERVED','CASH_ORDER_REJECTED')
                """));
        }
        if ("waiter".equals(scope)) {
            if (!staff.roles().stream().anyMatch(r -> r.equals("WAITER") ||
                r.equals("CASHIER") || r.equals("MANAGER") || r.equals("ADMIN"))) throw forbidden();
            if ("games".equals(topic)) {
                if (orderId != null) badTopic();
                boolean supervisor = staff.roles().stream().anyMatch(r ->
                    r.equals("CASHIER") || r.equals("MANAGER") || r.equals("ADMIN"));
                return await(after, stillAuthorized, staff.staffId(), LivePollCapacity.Kind.STAFF,
                    () -> supervisor
                        ? cursor("SELECT COALESCE(MAX(sequence_id),0) FROM live_game_signals")
                        : cursor("""
                            SELECT COALESCE(MAX(s.sequence_id),0) FROM live_game_signals s
                            JOIN physical_game_loans l ON l.id=s.loan_id
                            JOIN table_visits v ON v.id=l.visit_id
                            WHERE v.opened_by=?
                            """,staff.staffId()));
            }
            if ("tables".equals(topic)) {
                if (orderId != null) badTopic();
                // Every waiter may see table occupancy open/close (same as
                // GET /waiter/tables). Only the owner/supervisor can observe
                // secret-rotation metadata, never the proof itself.
                boolean supervisor = staff.roles().stream().anyMatch(r ->
                    r.equals("CASHIER") || r.equals("MANAGER") || r.equals("ADMIN"));
                return await(after, stillAuthorized, staff.staffId(), LivePollCapacity.Kind.STAFF, () -> supervisor
                    ? cursor("SELECT COALESCE(MAX(sequence_id),0) FROM live_table_signals")
                    : cursor("""
                        SELECT COALESCE(MAX(s.sequence_id),0)
                        FROM live_table_signals s
                        JOIN table_visits v ON v.id=s.visit_id
                        WHERE s.event_type IN ('TABLE_VISIT_OPENED','TABLE_VISIT_CLOSED')
                           OR (s.event_type='TABLE_JOIN_PROOF_ROTATED' AND v.opened_by=?)
                        """,staff.staffId()));
            }
            if (!"waiter".equals(topic) || orderId != null) badTopic();
            if (staff.roles().stream().anyMatch(r -> r.equals("CASHIER") ||
                r.equals("MANAGER") || r.equals("ADMIN"))) {
                return await(after, stillAuthorized, staff.staffId(), LivePollCapacity.Kind.STAFF, () -> cursor("""
                    SELECT COALESCE(MAX(sequence_id),0) FROM live_order_signals
                    WHERE event_type IN ('ORDER_READY','ORDER_SERVED','CASH_PAYMENT_PAID')
                    """));
            }
            return await(after, stillAuthorized, staff.staffId(), LivePollCapacity.Kind.STAFF, () -> cursor("""
                SELECT COALESCE(MAX(l.sequence_id),0)
                FROM live_order_signals l
                JOIN orders o ON o.id=l.order_id
                JOIN table_visits v ON v.id=o.visit_id
                WHERE v.opened_by=?
                  AND l.event_type IN ('ORDER_READY','ORDER_SERVED','CASH_PAYMENT_PAID')
                """,staff.staffId()));
        }
        badTopic();
        throw new IllegalStateException("Invalid scope");
    }

    private Cursor await(Long after, Supplier<Boolean> stillAuthorized,
                         UUID principalId, LivePollCapacity.Kind kind,
                         Supplier<Long> position) throws InterruptedException {
        if (after != null && after < 0) throw new ApiFailureException(
            HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid event cursor");
        if (after == null) return new Cursor(position.get());
        LivePollCapacity.Permit permit = admission.acquire(kind, principalId);
        if (permit == null) throw new ApiFailureException(
            HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many live subscribers");
        try (permit) {
            long deadline = System.nanoTime() + LONG_POLL_MS * 1_000_000L;
            while (true) {
                if (!stillAuthorized.get()) throw new ApiFailureException(
                    HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","Session expired");
                long observedGeneration = wakeup.generation();
                long current = position.get();
                if (current != after) return new Cursor(current);
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0) return new Cursor(current);
                wakeup.await(observedGeneration, Math.min(RECOVERY_CHECK_MS, remaining));
            }
        }
    }

    private long cursor(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class,args);
        return value == null ? 0 : value;
    }

    private boolean existsOrder(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM orders WHERE id=?)",Boolean.class,id));
    }
    private void badTopic() { throw new ApiFailureException(
        HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid live topic"); }
    private ApiFailureException missing() { return new ApiFailureException(
        HttpStatus.NOT_FOUND,"NOT_FOUND","Order not found"); }
    private ApiFailureException forbidden() { return new ApiFailureException(
        HttpStatus.FORBIDDEN,"FORBIDDEN","Access denied"); }
}
