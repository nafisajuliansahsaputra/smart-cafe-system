package com.natsx.smartcafe.common.events;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crash-safe, multi-replica relay for ORDER, TABLE_VISIT, GAME_LOAN and GAME_INVENTORY changes. A PostgreSQL
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

    private record Pending(UUID id, UUID aggregateId, String aggregateType, String eventType) {}

    @Transactional
    public int dispatchBatch() {
        Boolean leader = jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(712, 2026)", Boolean.class);
        if (!Boolean.TRUE.equals(leader)) return 0;

        List<Pending> pending = jdbc.query("""
            SELECT id, aggregate_id, aggregate_type, event_type
            FROM outbox_events
            WHERE aggregate_type IN ('ORDER','TABLE_VISIT','GAME_LOAN','GAME_INVENTORY') AND live_published_at IS NULL
            ORDER BY created_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """, (rs, row) -> new Pending(
                rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("event_type")), BATCH_SIZE);

        for (Pending event : pending) {
            int inserted;
            if ("ORDER".equals(event.aggregateType())) {
                inserted = jdbc.update("""
                    INSERT INTO live_order_signals(outbox_id, order_id, event_type)
                    VALUES (?,?,?)
                    ON CONFLICT (outbox_id) DO NOTHING
                    """, event.id(), event.aggregateId(), event.eventType());
            } else if ("TABLE_VISIT".equals(event.aggregateType())) {
                // Resolve the table from the durable visit, not an untrusted
                // outbox JSON payload. Both FK constraints fail closed.
                inserted = jdbc.update("""
                    INSERT INTO live_table_signals(outbox_id, visit_id, table_id, event_type)
                    SELECT ?, v.id, v.table_id, ? FROM table_visits v WHERE v.id=?
                    ON CONFLICT (outbox_id) DO NOTHING
                    """, event.id(), event.eventType(), event.aggregateId());
                if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM live_table_signals WHERE outbox_id=?)
                    """, Boolean.class, event.id()))) {
                    throw new IllegalStateException("Table visit event has no visit");
                }
            } else if ("GAME_LOAN".equals(event.aggregateType())) {
                // Resolve the loan from persisted relational state, never outbox JSON.
                // A missing loan must not be acknowledged as a published event.
                inserted = jdbc.update("""
                    INSERT INTO live_game_signals(outbox_id,loan_id,event_type)
                    SELECT ?, l.id, ? FROM physical_game_loans l WHERE l.id=?
                    ON CONFLICT (outbox_id) DO NOTHING
                    """, event.id(), event.eventType(), event.aggregateId());
                if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM live_game_signals WHERE outbox_id=?)
                    """, Boolean.class, event.id()))) {
                    throw new IllegalStateException("Game loan event has no loan");
                }
            } else if ("GAME_INVENTORY".equals(event.aggregateType())) {
                // A stock change is visible to authenticated catalogue viewers,
                // but the signal never contains copy codes, notes or staff IDs.
                // Reject an orphaned game ID without acknowledging publication.
                inserted = jdbc.update("""
                    INSERT INTO live_game_signals(outbox_id,game_id,event_type)
                    SELECT ?,g.id,? FROM physical_games g WHERE g.id=?
                    ON CONFLICT (outbox_id) DO NOTHING
                    """,event.id(),event.eventType(),event.aggregateId());
                if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM live_game_signals WHERE outbox_id=?)
                    """,Boolean.class,event.id()))) {
                    throw new IllegalStateException("Game inventory event has no game");
                }
            } else {
                throw new IllegalStateException("Unknown live aggregate type");
            }
            int changed = jdbc.update("""
                UPDATE outbox_events SET live_published_at=now()
                WHERE id=? AND live_published_at IS NULL
                """, event.id());
            if (changed != 1) {
                throw new IllegalStateException("Outbox relay lost publication ownership");
            }
            // PostgreSQL NOTIFY is only a commit-time wake-up, not a secret
            // or durable message. Subscribers always query scoped cursors.
            if (inserted == 1) {
                jdbc.query("SELECT pg_notify('scs_order_live', ?)",
                    (RowCallbackHandler) rs -> { }, event.aggregateId().toString());
            }
        }
        return pending.size();
    }
}
