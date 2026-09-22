package com.blockevidence.backend.sync;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * G3 (design docs/G3_SYNC_DESIGN.md section 9): the "eventSync" /actuator/health component. UNKNOWN before the
 * listener's first connection attempt has completed (matches LedgerHealthIndicator's honesty for the brief
 * startup window); UP once connected with fewer than {@link EventSyncListener#UNHEALTHY_AFTER_CONSECUTIVE_FAILURES}
 * consecutive stream-level failures since; DOWN at or past that many - a signal for an operator to look, NOT a
 * statement that sync has stopped trying (it never stops trying, design section 6).
 */
@Component
@Profile("!memory-ledger")
public class EventSyncHealthIndicator implements HealthIndicator {

    private final EventSyncListener listener;

    public EventSyncHealthIndicator(EventSyncListener listener) {
        this.listener = listener;
    }

    @Override
    public Health health() {
        if (!listener.hasEverConnected()) {
            return Health.unknown().withDetail("detail", "Event sync has not connected yet").build();
        }
        int failures = listener.consecutiveFailures();
        Health.Builder builder = failures >= EventSyncListener.UNHEALTHY_AFTER_CONSECUTIVE_FAILURES
                ? Health.down()
                : Health.up();
        builder.withDetail("consecutiveFailures", failures);
        listener.lastActivityAt().ifPresent(t -> builder.withDetail("lastActivityAt", t.toString()));
        return builder.build();
    }
}
