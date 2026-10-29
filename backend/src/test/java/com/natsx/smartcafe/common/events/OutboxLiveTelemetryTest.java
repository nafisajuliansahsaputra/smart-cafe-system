package com.natsx.smartcafe.common.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

class OutboxLiveTelemetryTest {
    @Test void publishesOnlyAggregateCountsAndMarksFailedReadsUnknown() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString()))
            .thenReturn(Map.of("pending", 7L, "oldest_seconds", 42L))
            .thenThrow(new DataAccessResourceFailureException("synthetic test outage"));
        { 
            var registry = new SimpleMeterRegistry();
            var monitor = new OutboxLiveTelemetry(jdbc, registry, false);
            assertEquals(-1L, monitor.pending());
            monitor.sample();
            assertEquals(7L, monitor.pending());
            assertEquals(42L, monitor.oldestSeconds());
            assertEquals(7.0, registry.get("scs.live.outbox.pending").gauge().value());
            monitor.sample();
            assertEquals(-1L, monitor.pending());
            assertEquals(-1L, monitor.oldestSeconds());
            assertEquals(1.0, registry.get("scs.live.outbox.sample.failures").counter().count());
        }
    }

    @Test void disabledSchedulerDoesNotQueryDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        { 
            var registry = new SimpleMeterRegistry();
            new OutboxLiveTelemetry(jdbc, registry, false).tick();
            verifyNoInteractions(jdbc);
        }
    }
}
