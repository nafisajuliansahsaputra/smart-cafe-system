package com.natsx.smartcafe.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.GuestPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties="SCS_JOIN_THROTTLE_SECRET=only-for-integration-tests-not-for-deployments")
class SandboxDigitalAttemptServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("scs_fake_payment").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private record Fixture(UUID order,UUID visit,UUID guest,UUID intent) {
        GuestPrincipal owner() { return new GuestPrincipal(guest,visit,"synthetic-only"); }
    }

    private SandboxDigitalAttemptService service() {
        Clock clock=Clock.systemUTC();
        return new SandboxDigitalAttemptService(jdbc,new TransactionTemplate(manager),
            new SandboxFakePaymentGateway(clock),clock);
    }

    private Fixture seed(String method) {
        UUID staff=UUID.randomUUID(),table=UUID.randomUUID(),visit=UUID.randomUUID();
        UUID guest=UUID.randomUUID(),order=UUID.randomUUID(),intent=UUID.randomUUID();
        jdbc.update("INSERT INTO staff_users(id,login,password_hash) VALUES (?,?,?)",
            staff,"fake-"+staff,"not-a-real-credential");
        jdbc.update("INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES (?,?,?,?)",
            table,table.toString(),"fake-"+table,4);
        jdbc.update("""
            INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
            VALUES (?,?,?,?)
            """,visit,table,staff,"0".repeat(64));
        jdbc.update("INSERT INTO visit_guests(id,visit_id,display_name) VALUES (?,?,?)",
            guest,visit,"Synthetic Sandbox Guest");
        jdbc.update("""
            INSERT INTO orders(id,visit_id,guest_id,subtotal_idr,total_idr,pricing_policy_version)
            VALUES (?,?,?,27000,27000,'M3-SANDBOX-NO-PAY')
            """,order,visit,guest);
        jdbc.update("""
            INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,aggregate_status)
            VALUES (?,?,?,?,?)
            """,intent,order,method,27000,method.equals("CASH")?"AWAITING_CASH":"PENDING");
        return new Fixture(order,visit,guest,intent);
    }

    private void assertNotSettled(Fixture fixture) {
        assertEquals("PENDING",jdbc.queryForObject("""
            SELECT aggregate_status FROM payment_intents WHERE id=?
            """,String.class,fixture.intent()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT amount_paid_idr FROM payment_intents WHERE id=?
            """,Long.class,fixture.intent()));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM receipts WHERE order_id=?
            """,Integer.class,fixture.order()));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM kitchen_tickets WHERE order_id=?
            """,Integer.class,fixture.order()));
    }

    @Test void createsDurableNonPayableAttemptAndReplaysSameKey() {
        var fixture=seed("QRIS");
        var service=service();
        var first=service.create(fixture.owner(),fixture.order(),
            PaymentGateway.Method.QRIS,"scs_fake_key_001");
        assertEquals("PENDING",first.state());
        assertEquals("FAKE_QRIS_NOT_PAYABLE",first.nonPayableDisplayHint());
        assertEquals(SandboxFakePaymentGateway.ENVIRONMENT,first.environment());
        assertTrue(first.providerReference().startsWith("FAKE_"));
        assertEquals(27000L,first.amountIdr());
        assertEquals(first,service.create(fixture.owner(),fixture.order(),
            PaymentGateway.Method.QRIS,"scs_fake_key_001"));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts WHERE payment_intent_id=?
            """,Integer.class,fixture.intent()));
        assertNotSettled(fixture);
        assertEquals(409,assertThrows(ApiFailureException.class,()->service.create(
            fixture.owner(),fixture.order(),PaymentGateway.Method.QRIS,
            "scs_second_key_001")).status().value());
    }

    @Test void vaAttemptsAndExpiredRetryUseNewKeyWithoutReopeningOldOne() {
        var fixture=seed("VA");
        var service=service();
        var first=service.create(fixture.owner(),fixture.order(),
            PaymentGateway.Method.VA,"scs_fake_va_first");
        assertEquals("FAKE_VA_NOT_PAYABLE",first.nonPayableDisplayHint());
        jdbc.update("""
            UPDATE digital_payment_attempts SET state='EXPIRED',updated_at=now()
            WHERE id=?
            """,first.id());
        var old=service.create(fixture.owner(),fixture.order(),
            PaymentGateway.Method.VA,"scs_fake_va_first");
        assertEquals("EXPIRED",old.state());
        assertEquals(first.id(),old.id());
        var next=service.create(fixture.owner(),fixture.order(),
            PaymentGateway.Method.VA,"scs_fake_va_retry");
        assertNotEquals(first.id(),next.id());
        assertNotEquals(first.providerReference(),next.providerReference());
        assertEquals(2,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts WHERE payment_intent_id=?
            """,Integer.class,fixture.intent()));
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts
            WHERE payment_intent_id=? AND state='PENDING'
            """,Integer.class,fixture.intent()));
        assertNotSettled(fixture);
    }

    @Test void deniesForeignGuestWrongVisitCashIntentAndMismatchedMethod() {
        var fixture=seed("QRIS");
        var service=service();
        var foreign=new GuestPrincipal(UUID.randomUUID(),fixture.visit(),"synthetic-only");
        var alienVisit=new GuestPrincipal(fixture.guest(),UUID.randomUUID(),"synthetic-only");
        for(var badGuest:List.of(foreign,alienVisit)) {
            assertEquals(404,assertThrows(ApiFailureException.class,()->service.create(
                badGuest,fixture.order(),PaymentGateway.Method.QRIS,"scs_foreign_key"))
                .status().value());
        }
        assertEquals(404,assertThrows(ApiFailureException.class,()->service.create(
            fixture.owner(),UUID.randomUUID(),PaymentGateway.Method.QRIS,
            "scs_unknown_key")).status().value());
        assertEquals(409,assertThrows(ApiFailureException.class,()->service.create(
            fixture.owner(),fixture.order(),PaymentGateway.Method.VA,
            "scs_wrong_method")).status().value());
        assertEquals(400,assertThrows(ApiFailureException.class,()->service.create(
            fixture.owner(),fixture.order(),PaymentGateway.Method.QRIS,
            "short")).status().value());
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts WHERE payment_intent_id=?
            """,Integer.class,fixture.intent()));
        var cash=seed("CASH");
        assertEquals(409,assertThrows(ApiFailureException.class,()->service.create(
            cash.owner(),cash.order(),PaymentGateway.Method.QRIS,
            "scs_cash_denied")).status().value());
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts WHERE payment_intent_id=?
            """,Integer.class,cash.intent()));
    }

    @Test void rejectsSettledOrClosedVisitWithoutNewAttempt() {
        var fixture=seed("QRIS");
        jdbc.update("""
            UPDATE table_visits SET status='CLOSED',closed_at=now()
            WHERE id=?
            """,fixture.visit());
        assertEquals(409,assertThrows(ApiFailureException.class,()->service().create(
            fixture.owner(),fixture.order(),PaymentGateway.Method.QRIS,"scs_closed_key"))
            .status().value());
        jdbc.update("UPDATE table_visits SET status='ACTIVE',closed_at=NULL WHERE id=?",
            fixture.visit());
        jdbc.update("""
            UPDATE payment_intents SET aggregate_status='PAID',amount_paid_idr=amount_due_idr
            WHERE id=?
            """,fixture.intent());
        assertEquals(409,assertThrows(ApiFailureException.class,()->service().create(
            fixture.owner(),fixture.order(),PaymentGateway.Method.QRIS,"scs_paid_key"))
            .status().value());
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts WHERE payment_intent_id=?
            """,Integer.class,fixture.intent()));
    }

    @Test void concurrentReplayKeepsOneAttemptAndNoMoneyChange() throws Exception {
        var fixture=seed("QRIS");
        var service=service();
        var pool=Executors.newFixedThreadPool(5);
        var latch=new CountDownLatch(1);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<SandboxDigitalAttemptService.Attempt>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{
                latch.await();
                return service.create(fixture.owner(),fixture.order(),
                    PaymentGateway.Method.QRIS,"scs_concurrent_key");
            }));
            latch.countDown();
            var first=futures.getFirst().get(30,TimeUnit.SECONDS);
            for(var future:futures)assertEquals(first,future.get(30,TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts WHERE payment_intent_id=?
            """,Integer.class,fixture.intent()));
        assertNotSettled(fixture);
    }

    @Test void competingKeysCannotBothOpenUnresolvedAttempts() throws Exception {
        var fixture=seed("QRIS");
        var service=service();
        var pool=Executors.newFixedThreadPool(2);
        var latch=new CountDownLatch(1);
        try {
            var outcomes=new ArrayList<java.util.concurrent.Future<String>>();
            for(String key:List.of("scs_parallel_first","scs_parallel_second")) {
                outcomes.add(pool.submit(()->{
                    latch.await();
                    try {
                        return service.create(fixture.owner(),fixture.order(),
                            PaymentGateway.Method.QRIS,key).state();
                    } catch(ApiFailureException ex) {
                        return String.valueOf(ex.status().value());
                    }
                }));
            }
            latch.countDown();
            List<String> results=List.of(outcomes.get(0).get(30,TimeUnit.SECONDS),
                outcomes.get(1).get(30,TimeUnit.SECONDS));
            assertTrue(results.contains("PENDING"),results.toString());
            assertTrue(results.contains("409"),results.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1,jdbc.queryForObject("""
            SELECT count(*) FROM digital_payment_attempts
            WHERE payment_intent_id=? AND state='PENDING'
            """,Integer.class,fixture.intent()));
        assertNotSettled(fixture);
    }
}
