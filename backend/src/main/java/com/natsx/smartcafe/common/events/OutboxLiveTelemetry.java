package com.natsx.smartcafe.common.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Low-frequency aggregated backlog metrics for both ORDER and TABLE_VISIT.
 * Emits counts/seconds only. No customer, payment or bearer data is logged.
 * -1 means unknown after a failed sample; never report stale healthy values.
 */
@Component
public class OutboxLiveTelemetry {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final AtomicLong pending = new AtomicLong(-1);
    private final AtomicLong oldestSeconds = new AtomicLong(-1);
    private final Counter failures;

    public OutboxLiveTelemetry(JdbcTemplate jdbc, MeterRegistry registry,
        @Value("${SCS_LIVE_TELEMETRY_ENABLED:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.failures = registry.counter("scs.live.outbox.sample.failures");
        Gauge.builder("scs.live.outbox.pending", pending, AtomicLong::get)
            .description("Unpublished live outbox rows; -1 means unknown")
            .register(registry);
        Gauge.builder("scs.live.outbox.oldest.seconds", oldestSeconds, AtomicLong::get)
            .description("Age of oldest unpublished live event; -1 means unknown")
            .register(registry);
    }

    @Scheduled(fixedDelayString = "${SCS_LIVE_TELEMETRY_INTERVAL_MS:30000}",
        initialDelayString = "${SCS_LIVE_TELEMETRY_INTERVAL_MS:30000}")
    public void tick() {
        if (enabled) sample();
    }

    /** Also callable directly by deterministic tests. Does not mutate events. */
    public void sample() {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) AS pending,
                  COALESCE(EXTRACT(EPOCH FROM (now()-MIN(created_at))),0)::bigint
                    AS oldest_seconds
                FROM outbox_events
                WHERE aggregate_type IN ('ORDER','TABLE_VISIT')
                  AND live_published_at IS NULL
                """);
            long count = ((Number)row.get("pending")).longValue();
            long seconds = ((Number)row.get("oldest_seconds")).longValue();
            pending.set(count);
            oldestSeconds.set(seconds);
        } catch (RuntimeException exception) {
            pending.set(-1);
            oldestSeconds.set(-1);
            failures.increment();
        }
    }

    public long pending() { return pending.get(); }
    public long oldestSeconds() { return oldestSeconds.get(); }
}
