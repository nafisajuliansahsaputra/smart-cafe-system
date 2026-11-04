package com.natsx.smartcafe.common.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Admission control for Spring MVC's blocking live-cursor long polls.
 *
 * One credential cannot monopolize the global budget. No bearer tokens or
 * guest/staff identifiers are ever used as metric tags or logged.
 */
@Component
public class LivePollCapacity {
    public enum Kind { GUEST, STAFF }
    private record Identity(Kind kind, UUID id) {}

    private final int maxGlobal;
    private final int maxGuest;
    private final int maxStaff;
    private final Semaphore global;
    private final ConcurrentHashMap<Identity, Integer> inUse = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final Counter accepted;
    private final Counter completed;
    private final Counter deniedGlobal;
    private final Counter deniedIdentity;
    private final Timer occupancy;

    public LivePollCapacity(
        @Value("${SCS_LIVE_MAX_LONG_POLLS:48}") int maxGlobal,
        @Value("${SCS_LIVE_MAX_GUEST_POLLS:2}") int maxGuest,
        @Value("${SCS_LIVE_MAX_STAFF_POLLS:6}") int maxStaff,
        MeterRegistry registry) {
        if (maxGlobal < 1 || maxGlobal > 128
            || maxGuest < 1 || maxGuest > maxGlobal
            || maxStaff < 1 || maxStaff > maxGlobal) {
            throw new IllegalArgumentException("Invalid bounded live poll admission limits");
        }
        this.maxGlobal = maxGlobal;
        this.maxGuest = maxGuest;
        this.maxStaff = maxStaff;
        global = new Semaphore(maxGlobal, true);
        accepted = registry.counter("scs.live.poll.accepted");
        completed = registry.counter("scs.live.poll.completed");
        deniedGlobal = registry.counter("scs.live.poll.rejected", "reason", "global");
        deniedIdentity = registry.counter("scs.live.poll.rejected", "reason", "per_identity");
        occupancy = Timer.builder("scs.live.poll.occupied")
            .description("How long an accepted long poll occupied an admission slot")
            .register(registry);
        Gauge.builder("scs.live.poll.active", active, AtomicInteger::get)
            .description("Current admitted live cursor long polls")
            .register(registry);
        Gauge.builder("scs.live.poll.capacity", () -> maxGlobal)
            .description("Configured global live cursor admission budget")
            .register(registry);
    }

    public Permit acquire(Kind kind, UUID principalId) {
        if (kind == null || principalId == null) {
            throw new IllegalArgumentException("Live poll requires authenticated principal");
        }
        if (!global.tryAcquire()) {
            deniedGlobal.increment();
            return null;
        }
        Identity identity = new Identity(kind, principalId);
        AtomicBoolean acquired = new AtomicBoolean();
        int limit = kind == Kind.GUEST ? maxGuest : maxStaff;
        inUse.compute(identity, (key, count) -> {
            int current = count == null ? 0 : count;
            if (current < limit) {
                acquired.set(true);
                return current + 1;
            }
            return current;
        });
        if (!acquired.get()) {
            global.release();
            deniedIdentity.increment();
            return null;
        }
        active.incrementAndGet();
        accepted.increment();
        return new Permit(identity);
    }

    public int activeCount() { return active.get(); }

    public final class Permit implements AutoCloseable {
        private final Identity identity;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final long startedAt = System.nanoTime();

        private Permit(Identity identity) { this.identity = identity; }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            inUse.compute(identity, (key, count) -> {
                if (count == null || count <= 0) {
                    throw new IllegalStateException("Live poll admission counter underflow");
                }
                return count == 1 ? null : count - 1;
            });
            active.decrementAndGet();
            global.release();
            completed.increment();
            occupancy.record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
        }
    }
}
