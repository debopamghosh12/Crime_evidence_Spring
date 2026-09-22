package com.blockevidence.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import com.blockevidence.backend.ledger.LedgerAction;
import com.blockevidence.backend.ledger.LedgerEvidenceEvent;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.notification.NotificationService;
import org.junit.jupiter.api.Test;

/** G3 (design docs/G3_SYNC_DESIGN.md section 5): the atomic unit of work. */
class EventProcessorTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final EvidenceActivityRepository activities = mock(EvidenceActivityRepository.class);
    final EvidenceProjectionRepository projections = mock(EvidenceProjectionRepository.class);
    final LedgerSyncCheckpointRepository checkpoints = mock(LedgerSyncCheckpointRepository.class);
    final NotificationService notifications = mock(NotificationService.class);
    final EventProcessor processor = new EventProcessor(activities, projections, checkpoints, notifications, clock);

    LedgerEvidenceRecord record(int version) {
        return new LedgerEvidenceRecord("EV-1", "CASE-1", EvidenceType.PHYSICAL, EvidenceStatus.COLLECTED, version,
                "m", "a".repeat(64), null, null, null, "collector-1", "COLLECTOR", clock.instant(), "collector-1",
                "COLLECTOR", clock.instant(), LedgerAction.CREATED, "reason", "collector-1",
                LedgerEvidenceRecord.Disposal.none(), LedgerEvidenceRecord.Transfer.none());
    }

    LedgerEvidenceEvent event() {
        return new LedgerEvidenceEvent("EV-1", 1, "tx-1", 42L, LedgerAction.CREATED);
    }

    @Test
    void aGenuinelyNewEventUpsertsTheProjectionAndAdvancesTheCheckpoint() {
        when(activities.insertIfAbsent(any(), eq("tx-1"), eq(42L), eq("EV-1"), eq(1), eq("CREATED"), eq("CASE-1"),
                eq("COLLECTED"), eq("collector-1"), eq("COLLECTOR"), eq("reason"), any(), any())).thenReturn(1);

        boolean applied = processor.apply(event(), record(1));

        assertThat(applied).isTrue();
        verify(projections).upsert("EV-1", "CASE-1", "PHYSICAL", "COLLECTED", 1, "collector-1", "collector-1",
                clock.instant(), clock.instant(), "CREATED", "reason");
        verify(checkpoints).advance(42L, "tx-1", clock.instant());
    }

    @Test
    void aRedeliveredEventTouchesNeitherTheProjectionNorTheCheckpoint() {
        when(activities.insertIfAbsent(any(), eq("tx-1"), any(long.class), any(), any(int.class), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(0);

        boolean applied = processor.apply(event(), record(1));

        assertThat(applied).isFalse();
        verify(projections, never()).upsert(any(), any(), any(), any(), any(int.class), any(), any(), any(), any(),
                any(), any());
        verify(checkpoints, never()).advance(any(long.class), any(), any());
    }

    @Test
    void eachEventGetsItsOwnRandomActivityId() {
        when(activities.insertIfAbsent(any(), any(), any(long.class), any(), any(int.class), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(1);

        processor.apply(event(), record(1));
        processor.apply(new LedgerEvidenceEvent("EV-1", 2, "tx-2", 43L, LedgerAction.METADATA_UPDATED), record(2));

        var idCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(activities, times(2)).insertIfAbsent(idCaptor.capture(), any(), any(long.class), any(), any(int.class),
                any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(idCaptor.getAllValues()).doesNotHaveDuplicates();
    }
}
