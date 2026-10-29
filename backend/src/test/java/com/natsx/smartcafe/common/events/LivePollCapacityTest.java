package com.natsx.smartcafe.common.events;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LivePollCapacityTest {
    @Test void oneSessionCannotStarveOtherGuestsAndPermitClosesOnlyOnce() {
        try (var registry = new SimpleMeterRegistry()) {
            var capacity = new LivePollCapacity(3, 2, 2, registry);
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            var a = capacity.acquire(LivePollCapacity.Kind.GUEST, first);
            var b = capacity.acquire(LivePollCapacity.Kind.GUEST, first);
            assertNotNull(a);
            assertNotNull(b);
            assertNull(capacity.acquire(LivePollCapacity.Kind.GUEST, first));
            var c = capacity.acquire(LivePollCapacity.Kind.GUEST, second);
            assertNotNull(c);
            assertNull(capacity.acquire(LivePollCapacity.Kind.STAFF, UUID.randomUUID()));
            assertEquals(3, capacity.activeCount());

            a.close();
            a.close();
            assertEquals(2, capacity.activeCount());
            var d = capacity.acquire(LivePollCapacity.Kind.STAFF, UUID.randomUUID());
            assertNotNull(d);
            c.close();
            b.close();
            d.close();
            assertEquals(0, capacity.activeCount());
            assertNotNull(capacity.acquire(LivePollCapacity.Kind.GUEST, first));
            assertEquals(1.0, registry.get("scs.live.poll.rejected")
                .tag("reason", "per_identity").counter().count());
            assertEquals(1.0, registry.get("scs.live.poll.rejected")
                .tag("reason", "global").counter().count());
        }
    }

    @Test void parallelAcquireNeverExceedsGlobalOrIdentityLimitAndReleasesEverySlot()
        throws Exception {
        try (var registry = new SimpleMeterRegistry();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var capacity = new LivePollCapacity(5, 2, 3, registry);
            UUID guest = UUID.randomUUID(), staff = UUID.randomUUID();
            var start = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var finished = new CountDownLatch(20);
            var permits = new java.util.concurrent.ConcurrentLinkedQueue<LivePollCapacity.Permit>();
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        var permit = capacity.acquire(index % 2 == 0
                            ? LivePollCapacity.Kind.GUEST : LivePollCapacity.Kind.STAFF,
                            index % 2 == 0 ? guest : staff);
                        if (permit != null) {
                            permits.add(permit);
                            release.await();
                            permit.close();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                }));
            }
            start.countDown();
            // Wait until all attempts have either acquired or been rejected.
            // A capped lock-free admission must never exceed its budget.
            for (int i = 0; i < 50; i++) {
                if (registry.find("scs.live.poll.accepted").counter().count()
                    + registry.find("scs.live.poll.rejected").tags("reason","global")
                        .counter().count()
                    + registry.find("scs.live.poll.rejected").tags("reason","per_identity")
                        .counter().count() >= 20) break;
                Thread.sleep(10);
            }
            assertTrue(capacity.activeCount() <= 5);
            assertTrue(capacity.activeCount() <= 2 + 3);
            assertEquals(5, capacity.activeCount());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(0, capacity.activeCount());
            assertEquals(5, capacity.acquire(LivePollCapacity.Kind.STAFF, UUID.randomUUID()) == null
                ? -1 : 5); // admits again after race; no leak
        }
    }

    @Test void invalidLimitsFailClosed() {
        try (var registry = new SimpleMeterRegistry()) {
            assertThrows(IllegalArgumentException.class,
                () -> new LivePollCapacity(0, 1, 1, registry));
            assertThrows(IllegalArgumentException.class,
                () -> new LivePollCapacity(129, 1, 1, registry));
            assertThrows(IllegalArgumentException.class,
                () -> new LivePollCapacity(3, 4, 1, registry));
            assertThrows(IllegalArgumentException.class,
                () -> new LivePollCapacity(3, 1, 4, registry));
        }
    }
}
