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

    /** Only explicit physical-loan state changes are published; never serials or guest data. */
    public void gameLoan(UUID loanId, String eventName) {
        if (!java.util.Set.of("GAME_LOAN_REQUESTED", "GAME_LOAN_ASSIGNED",
                "GAME_LOAN_HANDED_OVER", "GAME_LOAN_RETURN_REQUESTED",
                "GAME_LOAN_RETURNED",
                "GAME_LOAN_CANCELLED", "GAME_LOSS_REPORTED",
                "GAME_LOSS_DISMISSED", "GAME_LOSS_CONFIRMED").contains(eventName)) {
            throw new IllegalArgumentException("Unknown safe game loan event");
        }
        jdbc.update("""
            INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload_safe)
            VALUES ('GAME_LOAN',?,?,'{}'::jsonb)
            """,loanId,eventName);
    }

    /** Inventory change is public-to-authenticated-visit signal only. No serial,
     * borrower, condition note, or staff identity is sent to the live relay. */
    public void gameInventory(UUID gameId) {
        jdbc.update("""
            INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload_safe)
            VALUES ('GAME_INVENTORY',?,'GAME_INVENTORY_CHANGED','{}'::jsonb)
            """,gameId);
    }

    public void order(UUID orderId,String eventName) {
        jdbc.update("""
            INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload_safe)
            VALUES ('ORDER',?,?,'{}'::jsonb)
            """,orderId,eventName);
    }
}
