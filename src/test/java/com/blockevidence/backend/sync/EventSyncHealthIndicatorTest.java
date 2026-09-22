package com.blockevidence.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/** G3 (design section 9): the "eventSync" /actuator/health component. */
class EventSyncHealthIndicatorTest {

    final EventSyncListener listener = mock(EventSyncListener.class);
    final EventSyncHealthIndicator indicator = new EventSyncHealthIndicator(listener);

    @Test
    void unknownBeforeTheFirstConnection() {
        when(listener.hasEverConnected()).thenReturn(false);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void upWhenConnectedAndFewerThanTheUnhealthyThreshold() {
        when(listener.hasEverConnected()).thenReturn(true);
        when(listener.consecutiveFailures()).thenReturn(EventSyncListener.UNHEALTHY_AFTER_CONSECUTIVE_FAILURES - 1);
        when(listener.lastActivityAt()).thenReturn(Optional.of(Instant.parse("2026-03-01T10:00:00Z")));

        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("consecutiveFailures"))
                .isEqualTo(EventSyncListener.UNHEALTHY_AFTER_CONSECUTIVE_FAILURES - 1);
        assertThat(health.getDetails().get("lastActivityAt")).isEqualTo("2026-03-01T10:00:00Z");
    }

    @Test
    void downAtExactlyTheUnhealthyThresholdStillRetryingUnderneath() {
        when(listener.hasEverConnected()).thenReturn(true);
        when(listener.consecutiveFailures()).thenReturn(EventSyncListener.UNHEALTHY_AFTER_CONSECUTIVE_FAILURES);
        when(listener.lastActivityAt()).thenReturn(Optional.empty());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
