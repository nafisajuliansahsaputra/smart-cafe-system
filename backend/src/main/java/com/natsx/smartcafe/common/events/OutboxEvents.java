package com.natsx.smartcafe.common.events;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Same-transaction durable event recording. Dispatch worker is a later milestone. */
@Service
public class OutboxEvents {
    private final JdbcTemplate jdbc;
    public OutboxEvents(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void order(UUID orderId,String eventName) {
        jdbc.update("""
            INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload_safe)
            VALUES ('ORDER',?,?,'{}'::jsonb)
            """,orderId,eventName);
    }
}
