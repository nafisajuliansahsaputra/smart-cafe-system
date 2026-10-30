package com.natsx.smartcafe.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Only non-payable fake references; never invoke a real payment network. */
class SandboxFakePaymentGatewayTest {
    private static final Instant START=Instant.parse("2026-10-31T00:00:00Z");

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(START);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void advanceSeconds(long seconds) { now.updateAndGet(x->x.plusSeconds(seconds)); }
    }

    @Test void createsNonPayableQrisAndVaAndReplaysStableReference() {
        var clock=new AdjustableClock();
        var fake=new SandboxFakePaymentGateway(clock);
        UUID order=UUID.randomUUID();
        var qris=new PaymentGateway.CreateRequest(order,28000,
            PaymentGateway.Method.QRIS,"sandbox_test_123");
        var first=fake.create(qris);
        assertEquals(PaymentGateway.State.PENDING,first.state());
        assertEquals("FAKE_QRIS_NOT_PAYABLE",first.nonPayableDisplayHint());
        assertTrue(first.providerReference().startsWith("FAKE_"));
        assertEquals(first,fake.create(qris));
        var snapshot=fake.lookup(first.providerReference());
        assertEquals(order,snapshot.orderId());
        assertEquals(28000,snapshot.amountIdr());
        assertEquals(PaymentGateway.Method.QRIS,snapshot.method());
        assertEquals(SandboxFakePaymentGateway.ENVIRONMENT,snapshot.environment());
        assertEquals(PaymentGateway.State.PENDING,snapshot.state());
        var va=fake.create(new PaymentGateway.CreateRequest(
            UUID.randomUUID(),45000,PaymentGateway.Method.VA,"sandbox_test_124"));
        assertEquals("FAKE_VA_NOT_PAYABLE",va.nonPayableDisplayHint());
        assertNotEquals(first.providerReference(),va.providerReference());
        clock.advanceSeconds(901);
        assertEquals(PaymentGateway.State.EXPIRED,
            fake.lookup(first.providerReference()).state());
        assertEquals(PaymentGateway.State.EXPIRED,
            fake.lookup(va.providerReference()).state());
    }

    @Test void rejectsBadAmountsKeysAndUnknownReferences() {
        var fake=new SandboxFakePaymentGateway(Clock.fixed(START,ZoneOffset.UTC));
        var id=UUID.randomUUID();
        for(long amount:List.of(-1L,0L,1_000_000_000_001L)) {
            assertThrows(IllegalArgumentException.class,()->fake.create(
                new PaymentGateway.CreateRequest(id,amount,PaymentGateway.Method.QRIS,
                    "sandbox_safe_1")));
        }
        for(String key:List.of("short","space in key","';DROP TABLE","")) {
            assertThrows(IllegalArgumentException.class,()->fake.create(
                new PaymentGateway.CreateRequest(id,30000,PaymentGateway.Method.VA,key)));
        }
        assertThrows(IllegalArgumentException.class,()->fake.create(null));
        assertThrows(IllegalArgumentException.class,()->fake.lookup("not-a-fake-reference"));
    }

    @Test void sameIdempotencyKeyCannotSwitchAmountOrPaymentMethod() {
        var fake=new SandboxFakePaymentGateway(Clock.fixed(START,ZoneOffset.UTC));
        UUID id=UUID.randomUUID();
        var first=new PaymentGateway.CreateRequest(id,50000,
            PaymentGateway.Method.QRIS,"same_key_12345");
        fake.create(first);
        assertThrows(IllegalStateException.class,()->fake.create(
            new PaymentGateway.CreateRequest(id,60000,PaymentGateway.Method.QRIS,
                "same_key_12345")));
        assertThrows(IllegalStateException.class,()->fake.create(
            new PaymentGateway.CreateRequest(id,50000,PaymentGateway.Method.VA,
                "same_key_12345")));
        assertEquals(PaymentGateway.State.PENDING,
            fake.lookup(fake.create(first).providerReference()).state());
    }

    @Test void parallelReplaysReturnOneFakeAttempt() throws Exception {
        var fake=new SandboxFakePaymentGateway(Clock.fixed(START,ZoneOffset.UTC));
        var request=new PaymentGateway.CreateRequest(UUID.randomUUID(),94000,
            PaymentGateway.Method.QRIS,"parallel_key_12345");
        var pool=Executors.newFixedThreadPool(6);
        var latch=new CountDownLatch(1);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<PaymentGateway.Created>>();
            for(int i=0;i<12;i++) {
                tasks.add(pool.submit(()->{
                    latch.await();
                    return fake.create(request);
                }));
            }
            latch.countDown();
            var initial=tasks.getFirst().get(10,TimeUnit.SECONDS);
            for(var future:tasks) assertEquals(initial,future.get(10,TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
