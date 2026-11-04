package com.natsx.smartcafe.common.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Explicitly opt-in in deployment. Disabled in unrelated integration tests
 * for deterministic assertions; production requires dedicated monitoring.
 */
@Component
public class OutboxLiveWorker {
    private final OutboxLiveRelay relay;
    private final boolean enabled;
    private final Counter dispatched;
    private final Counter failures;

    public OutboxLiveWorker(OutboxLiveRelay relay,
                            @Value("${SCS_LIVE_DISPATCH_ENABLED:false}") boolean enabled,
                            MeterRegistry metrics) {
        this.relay = relay;
        this.enabled = enabled;
        dispatched = metrics.counter("scs.live.outbox.events.published");
        failures = metrics.counter("scs.live.outbox.dispatch.failures");
    }

    @Scheduled(fixedDelayString = "${SCS_LIVE_DISPATCH_DELAY_MS:1000}",
               initialDelayString = "${SCS_LIVE_DISPATCH_DELAY_MS:1000}")
    public void tick() {
        if (!enabled) return;
        // Do not catch database errors and claim successful delivery.
        // Spring logs failures; pending rows stay durable for retry.
        try {
            int published = relay.dispatchBatch();
            if (published > 0) dispatched.increment(published);
        } catch (RuntimeException ex) {
            failures.increment();
            throw ex; // Preserve Spring's scheduler failure logging and retry behavior.
        }
    }
}
