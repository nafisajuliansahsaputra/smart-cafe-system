package com.natsx.smartcafe.common.events;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crash-safe, multi-replica relay for ORDER outbox changes. A PostgreSQL
 * transaction advisory lock ensures only one instance assigns live sequence
 * numbers at a time, avoiding out-of-commit-order cursor gaps.
 *
 * The durable live marker is NOT outbox.processed_at: external business
 * integrations must get an independent delivery/reconciliation policy.
 */
@Service
public class OutboxLiveRelay {
    private static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;

    public OutboxLiveRelay(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Pending(UUID id, UUID orderId, String eventType) {}

    @Transactional
    public int dispatchBatch() {
        Boolean leader = jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(712, 2026)", Boolean.class);
        if (!Boolean.TRUE.equals(leader)) return 0;

        List<Pending> pending = jdbc.query("""
            SELECT id, aggregate_id, event_type
            FROM outbox_events
            WHERE aggregate_type='ORDER' AND live_published_at IS NULL
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT ?
            """, (rs, row) -> new Pending(
                rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type")), BATCH_SIZE);

        for (Pending event : pending) {
            jdbc.update("""
                INSERT INTO live_order_signals(outbox_id, order_id, event_type)
                VALUES (?,?,?)
                ON CONFLICT (outbox_id) DO NOTHING
                """, event.id(), event.orderId(), event.eventType());
            int changed = jdbc.update("""
                UPDATE outbox_events SET live_published_at=now()
                WHERE id=? AND live_published_at IS NULL
                """, event.id());
            if (changed != 1) {
                throw new IllegalStateException("Outbox relay lost publication ownership");
            }
            // PostgreSQL NOTIFY is transactional: listeners only wake after
            // the durable signal/marker commit, never before a rollback.
            // The payload is only a UUID, never any personal or payment data.
            jdbc.query("SELECT pg_notify('scs_order_live', ?)",
                (RowCallbackHandler) rs -> { }, event.orderId().toString());
        }
        return pending.size();
    }
}
