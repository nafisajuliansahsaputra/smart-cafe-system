package com.natsx.smartcafe.common.events;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Same-transaction durable event recording. The separate live relay does not consume business outbox processing state. */
@Service
public class OutboxEvents {
    private final JdbcTemplate jdbc;
    public OutboxEvents(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void tableVisit(UUID visitId, String eventName) {
        if (!java.util.Set.of("TABLE_VISIT_OPENED", "TABLE_JOIN_PROOF_ROTATED",
                              "TABLE_VISIT_CLOSED").contains(eventName)) {
            throw new IllegalArgumentException("Unknown safe table event");
        }
        jdbc.update("""
            INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload_safe)
            VALUES ('TABLE_VISIT',?,?,'{}'::jsonb)
            """,visitId,eventName);
    }

    public void order(UUID orderId,String eventName) {
        jdbc.update("""
            INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload_safe)
            VALUES ('ORDER',?,?,'{}'::jsonb)
            """,orderId,eventName);
    }
}
