package com.natsx.smartcafe.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * No real webhook, payment credential, network call or financial settlement.
 * Evidence remains untrusted forever in this sandbox-only increment.
 */
@Testcontainers
@SpringBootTest(properties="SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class SandboxPaymentEventInboxIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("scs_quarantine").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private record Fixture(UUID orderId,UUID intentId,UUID attemptId,String reference) {}
    private SandboxPaymentEventInbox inbox() {
        return new SandboxPaymentEventInbox(jdbc,new TransactionTemplate(manager));
    }

    private Fixture seed() {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),visit=UUID.randomUUID();
        UUID guest=UUID.randomUUID(),order=UUID.randomUUID(),intent=UUID.randomUUID();
        UUID attempt=UUID.randomUUID();
        String reference="FAKE_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"event-"+staff,"synthetic-disabled-secret");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"event-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"0".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic Event Guest");
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,37000,37000,'FAKE-EVENT-ONLY')
            """,order,visit,guest);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,'QRIS',37000,'PENDING')
            """,intent,order);
        jdbc.update("""
            INSERT INTO digital_payment_attempts(
                id,payment_intent_id,method,provider_key,provider_reference,
                idempotency_key,amount_idr,state,expires_at)
            VALUES (?,?,'QRIS','SANDBOX_FAKE',?,? ,37000,'PENDING',now()+interval '15 minutes')
            """,attempt,intent,reference,"event_key_"+UUID.randomUUID().toString().replace("-",""));
        return new Fixture(order,intent,attempt,reference);
    }

    private SandboxPaymentEventInbox.Observation observation(
        String eventId,String ref,long amount,String env,PaymentGateway.State state) {
        return new SandboxPaymentEventInbox.Observation(eventId,ref,PaymentGateway.Method.QRIS,
            state,amount,"IDR",env);
    }
    private String freshEvent() {
        return "event_"+UUID.randomUUID().toString().replace("-","");
    }
    private static final String FAKE_ENV="SANDBOX_FAKE_NOT_MONEY";

    private void assertNoSettlement(Fixture f) {
        assertEquals("PENDING",jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE id=?
            """,String.class,f.intentId()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT amount_paid_idr FROM payment_intents WHERE id=?
            """,Long.class,f.intentId()));
        assertEquals("SUBMITTED",jdbc.queryForObject(
            "SELECT status FROM orders WHERE id=?",String.class,f.orderId()));
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM receipts WHERE order_id=?",Integer.class,f.orderId()));
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM kitchen_tickets WHERE order_id=?",Integer.class,f.orderId()));
    }

    @Test void successClaimIsQuarantinedAndExactReplayIsIdempotent() {
        var f=seed();
        var inbox=inbox();
        var input=observation(freshEvent(),f.reference(),37000,FAKE_ENV,
            PaymentGateway.State.SUCCEEDED);
        var first=inbox.record(input);
        assertEquals("QUARANTINED",first.classification());
        assertEquals(f.attemptId(),first.attemptId());
        assertEquals(64,first.payloadFingerprint().length());
        assertEquals(first,inbox.record(input));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM sandbox_payment_event_inbox WHERE provider_event_id=?
            """,Integer.class,input.eventId()));
        assertEquals("PENDING",jdbc.queryForObject(
            "SELECT state FROM digital_payment_attempts WHERE id=?",
            String.class,f.attemptId()));
        assertNoSettlement(f);
        for(var conflicting : List.of(
            observation(input.eventId(),f.reference(),37001,FAKE_ENV,PaymentGateway.State.SUCCEEDED),
            observation(input.eventId(),f.reference(),37000,FAKE_ENV,PaymentGateway.State.FAILED),
            observation(input.eventId(),"FAKE_"+UUID.randomUUID().toString().replace("-",""),
                37000,FAKE_ENV,PaymentGateway.State.SUCCEEDED))) {
            var failure=assertThrows(ApiFailureException.class,()->inbox.record(conflicting));
            assertEquals(409,failure.status().value());
        }
        assertNoSettlement(f);
    }

    @Test void mismatchedUnknownLateAndContradictoryClaimsRemainEvidenceOnly() {
        var f=seed();
        var inbox=inbox();
        var missing=inbox.record(observation(freshEvent(),
            "FAKE_"+UUID.randomUUID().toString().replace("-",""),37000,
            FAKE_ENV,PaymentGateway.State.SUCCEEDED));
        assertEquals("UNKNOWN_REFERENCE",missing.classification());
        assertEquals(null,missing.attemptId());
        var amount=inbox.record(observation(freshEvent(),f.reference(),37001,FAKE_ENV,
            PaymentGateway.State.SUCCEEDED));
        assertEquals("FINANCIAL_MISMATCH",amount.classification());
        var env=inbox.record(observation(freshEvent(),f.reference(),37000,
            "PRODUCTION_REAL",PaymentGateway.State.SUCCEEDED));
        assertEquals("ENVIRONMENT_MISMATCH",env.classification());
        var wrongMethod=inbox.record(new SandboxPaymentEventInbox.Observation(
            freshEvent(),f.reference(),PaymentGateway.Method.VA,
            PaymentGateway.State.SUCCEEDED,37000,"IDR",FAKE_ENV));
        assertEquals("FINANCIAL_MISMATCH",wrongMethod.classification());
        jdbc.update("""
            UPDATE digital_payment_attempts SET state='EXPIRED',updated_at=now()
            WHERE id=?
            """,f.attemptId());
        var late=inbox.record(observation(freshEvent(),f.reference(),37000,FAKE_ENV,
            PaymentGateway.State.SUCCEEDED));
        assertEquals("LATE_SUCCESS",late.classification());
        assertNoSettlement(f);
        var next=seed();
        jdbc.update("UPDATE digital_payment_attempts SET state='FAILED' WHERE id=?",
            next.attemptId());
        var contradictory=inbox.record(observation(freshEvent(),next.reference(),
            37000,FAKE_ENV,PaymentGateway.State.SUCCEEDED));
        assertEquals("CONTRADICTORY_STATE",contradictory.classification());
        assertNoSettlement(next);
        assertEquals(6,jdbc.queryForObject("""
            SELECT count(*) FROM sandbox_payment_event_inbox WHERE id IN (?,?,?,?,?,?)
            """,Integer.class,missing.id(),amount.id(),env.id(),
            wrongMethod.id(),late.id(),contradictory.id()));
    }

    @Test void parallelReplaysStoreExactlyOneImmutableEvent() throws Exception {
        var f=seed();
        var inbox=inbox();
        var input=observation(freshEvent(),f.reference(),37000,FAKE_ENV,
            PaymentGateway.State.SUCCEEDED);
        var pool=Executors.newFixedThreadPool(5);
        var latch=new CountDownLatch(1);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<SandboxPaymentEventInbox.Evidence>>();
            for(int i=0;i<10;i++)futures.add(pool.submit(()->{
                latch.await();
                return inbox.record(input);
            }));
            latch.countDown();
            var first=futures.getFirst().get(30,TimeUnit.SECONDS);
            for(var future:futures)assertEquals(first,future.get(30,TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM sandbox_payment_event_inbox WHERE provider_event_id=?
            """,Integer.class,input.eventId()));
        assertNoSettlement(f);
    }

    @Test void quarantineRejectsMalformedInputAndEvidenceCannotBeEdited() {
        var f=seed();
        var inbox=inbox();
        var malformed=List.of(
            observation("short",f.reference(),37000,FAKE_ENV,PaymentGateway.State.PENDING),
            observation(freshEvent(),f.reference(),0,FAKE_ENV,PaymentGateway.State.PENDING),
            observation(freshEvent(),"not-a-reference",37000,FAKE_ENV,
                PaymentGateway.State.SUCCEEDED),
            observation(freshEvent(),f.reference(),37000,"bad lower",
                PaymentGateway.State.SUCCEEDED));
        for(var input:malformed)assertEquals(400,
            assertThrows(ApiFailureException.class,()->inbox.record(input)).status().value());
        assertEquals(0,jdbc.queryForObject(
            "SELECT count(*) FROM sandbox_payment_event_inbox WHERE attempt_id=?",
            Integer.class,f.attemptId()));
        var proof=inbox.record(observation(freshEvent(),f.reference(),37000,FAKE_ENV,
            PaymentGateway.State.SUCCEEDED));
        assertNotNull(proof.id());
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("""
            UPDATE sandbox_payment_event_inbox SET classification='ALREADY_SETTLED'
            WHERE id=?
            """,proof.id()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update(
            "DELETE FROM sandbox_payment_event_inbox WHERE id=?",proof.id()));
        assertEquals(1,jdbc.queryForObject(
            "SELECT count(*) FROM sandbox_payment_event_inbox WHERE id=?",
            Integer.class,proof.id()));
        assertNoSettlement(f);
    }
}
