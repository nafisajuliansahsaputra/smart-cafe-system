package com.natsx.smartcafe.common.events;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LiveCursorAsyncBridgeTest {
    @Test
    void disconnectCancellationInterruptsWaiterOnceAndDiscardsLateResult() throws Exception {
        var bridge = new LiveCursorAsyncBridge();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var task = bridge.prepare(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                throw ex;
            }
            throw new IllegalStateException("Unreachable after cancel");
        });
        var result = task.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        task.cancel(); // Same cleanup used by onError and onCompletion.
        task.cancel(); // Must remain idempotent.
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertFalse(result.hasResult(), "Abandoned request must not publish a fake success");
    }

    @Test
    void cancelBeforeStartNeverExecutesWork() {
        var bridge = new LiveCursorAsyncBridge();
        var called = new AtomicInteger();
        var pending = bridge.prepare(() -> {
            called.incrementAndGet();
            throw new IllegalStateException("Cancelled task must not run");
        });
        pending.cancel();
        assertFalse(pending.start().hasResult());
        assertEquals(0, called.get());
    }
    @Test
    void cancelledLongPollReleasesIdentityAndGlobalCapacity() throws Exception {
        var capacity = new LivePollCapacity(1, 1, 1, new SimpleMeterRegistry());
        var identity = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var bridge = new LiveCursorAsyncBridge();
        var pending = bridge.prepare(() -> {
            try (var permit = capacity.acquire(LivePollCapacity.Kind.STAFF, identity)) {
                assertNotNull(permit);
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    throw ex;
                }
                throw new IllegalStateException("Cancellation should interrupt the wait");
            }
        });
        pending.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(1, capacity.activeCount());
        assertNull(capacity.acquire(LivePollCapacity.Kind.STAFF, identity),
            "The same credential must not bypass the live admission cap");
        pending.cancel();
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        for (int i = 0; i < 50 && capacity.activeCount() > 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(0, capacity.activeCount(), "Cancellation must release both permits");
        var next = capacity.acquire(LivePollCapacity.Kind.STAFF, identity);
        assertNotNull(next, "The subscriber can reconnect after the abandoned wait");
        next.close();
        assertEquals(0, capacity.activeCount());
    }

}
