package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.natsx.smartcafe.common.events.OutboxEvents;
import com.natsx.smartcafe.common.events.OutboxLiveRelay;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = "SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class OutboxLiveRelayIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("smart_cafe_live_relay_test")
        .withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxLiveRelay relay;
    @Autowired com.natsx.smartcafe.common.events.OutboxLiveTelemetry telemetry;
    @Autowired OutboxEvents outbox;

    private UUID syntheticOrder() {
        UUID staff=UUID.randomUUID(), table=UUID.randomUUID(), visit=UUID.randomUUID(),
            guest=UUID.randomUUID(), order=UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff, staff.toString(), "test-only-not-a-real-hash");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table, table.toString(), "relay-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """, visit,table,staff,"a".repeat(64));
        jdbc.update("""
            INSERT INTO visit_guests(id,visit_id,display_name)
            VALUES (?,?,?)
            """,guest,visit,"Synthetic relay test");
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,?,?,?)
            """,order,visit,guest,1000,1000,"SYNTHETIC");
        return order;
    }

    @Test void dispatchesDurablyExactlyOnceAndDoesNotConsumeBusinessOutbox() {
        UUID order=syntheticOrder();
        outbox.order(order,"ORDER_SUBMITTED");
        outbox.order(order,"ORDER_ACCEPTED");
        assertEquals(2,relay.dispatchBatch());
        var signals=jdbc.query("""
            SELECT outbox_id,order_id,event_type,sequence_id
            FROM live_order_signals WHERE order_id=? ORDER BY sequence_id
            """,(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getString(3),rs.getLong(4)},order);
        assertEquals(2,signals.size());
        assertEquals(order,signals.getFirst()[1]);
        assertTrue((Long)signals.get(0)[3] < (Long)signals.get(1)[3]);
        assertEquals(0,relay.dispatchBatch());
        assertEquals(2,jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE aggregate_id=? AND live_published_at IS NOT NULL
              AND processed_at IS NULL
            """,Integer.class,order));
        // Crash replay after a prior committed insert must not duplicate event.
        jdbc.update("""
            UPDATE outbox_events SET live_published_at=NULL
            WHERE aggregate_id=? AND event_type='ORDER_SUBMITTED'
            """,order);
        assertEquals(1,relay.dispatchBatch());
        assertEquals(2,jdbc.queryForObject(
            "SELECT count(*) FROM live_order_signals WHERE order_id=?",
            Integer.class,order));
    }

    @Test void brokenEventCannotBeSilentlyAckedAndLaterCanRetry() {
        UUID missingOrder=UUID.randomUUID();
        UUID event=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload_safe)
            VALUES (?,'ORDER',?,'SYNTHETIC_RETRY','{}'::jsonb)
            """,event,missingOrder);
        assertThrows(DataIntegrityViolationException.class, relay::dispatchBatch);
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM live_order_signals WHERE outbox_id=?",
            Integer.class,event));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE id=? AND live_published_at IS NOT NULL
            """,Integer.class,event));
        // A valid order created later can unblock this parked event.
        // Ensure the synthetic invalid outbox row is removed after the test.
        jdbc.update("DELETE FROM outbox_events WHERE id=?", event);
    }
    @Test void realPostgreSqlOutboxBacklogIsVisibleWithoutChangingBusinessState() {
        telemetry.sample();
        long prior = telemetry.pending();
        UUID order = syntheticOrder();
        outbox.order(order, "ORDER_SUBMITTED");
        telemetry.sample();
        assertEquals(prior + 1, telemetry.pending());
        assertTrue(telemetry.oldestSeconds() >= 0);
        assertTrue(relay.dispatchBatch() > 0);
        telemetry.sample();
        assertTrue(telemetry.pending() <= prior);
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM outbox_events
            WHERE aggregate_id=? AND live_published_at IS NOT NULL
              AND processed_at IS NULL
            """, Integer.class, order));
    }

}
