package com.natsx.smartcafe.common.events;

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

    public OutboxLiveWorker(OutboxLiveRelay relay,
                            @Value("${SCS_LIVE_DISPATCH_ENABLED:false}") boolean enabled) {
        this.relay = relay;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${SCS_LIVE_DISPATCH_DELAY_MS:1000}",
               initialDelayString = "${SCS_LIVE_DISPATCH_DELAY_MS:1000}")
    public void tick() {
        if (!enabled) return;
        // Do not catch database errors and claim successful delivery.
        // Spring logs failures; pending rows stay durable for retry.
        relay.dispatchBatch();
    }
}
