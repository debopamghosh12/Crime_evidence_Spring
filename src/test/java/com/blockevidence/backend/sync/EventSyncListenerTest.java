package com.blockevidence.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.blockevidence.backend.ledger.Checkpoint;
import com.blockevidence.backend.ledger.LedgerAction;
import com.blockevidence.backend.ledger.LedgerEvidenceEvent;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.ledger.LedgerHistoryEntry;
import com.blockevidence.backend.ledger.LedgerService;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;
import org.junit.jupiter.api.Test;

/**
 * G3 (design docs/G3_SYNC_DESIGN.md sections 6, 9, 10): the listener loop's reconnect/backoff/health behaviour,
 * driven one connect-and-consume pass at a time via the package-private {@link EventSyncListener#runOnce()} - no
 * background thread, no real Fabric network. {@link EventProcessor} is mocked here: this class is about the LOOP,
 * not persistence (that is {@link EventProcessorTest}).
 */
class EventSyncListenerTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final FakeLedgerEventSource source = new FakeLedgerEventSource();
    final LedgerService ledger = mock(LedgerService.class);
    final EventProcessor processor = mock(EventProcessor.class);
    final LedgerSyncCheckpointRepository checkpoints = mock(LedgerSyncCheckpointRepository.class);
    final EventSyncListener listener = new EventSyncListener(source, ledger, processor, checkpoints, clock);

    LedgerEvidenceEvent event(String txId, int version) {
        return new LedgerEvidenceEvent("EV-1", version, txId, 10L + version, LedgerAction.CREATED);
    }

    /** Makes ledger.getHistory find this event's txId, as the listener requires (design section 4). Accumulates:
     *  calling it for several events of the same evidence id builds up a history all of them can be found in,
     *  exactly like a real item's history grows with each write. */
    final java.util.List<LedgerHistoryEntry> accumulatedHistory = new java.util.ArrayList<>();

    void stubHistory(LedgerEvidenceEvent e) {
        LedgerEvidenceRecord record = new LedgerEvidenceRecord("EV-1", "CASE-1", EvidenceType.PHYSICAL,
                EvidenceStatus.COLLECTED, e.version(), "m", "a".repeat(64), null, null, null, "collector-1",
                "COLLECTOR", clock.instant(), "collector-1", "COLLECTOR", clock.instant(), e.action(), "r",
                "collector-1", LedgerEvidenceRecord.Disposal.none(), LedgerEvidenceRecord.Transfer.none());
        accumulatedHistory.add(new LedgerHistoryEntry(e.txId(), clock.instant(), record));
        when(ledger.getHistory("EV-1")).thenReturn(List.copyOf(accumulatedHistory));
    }

    @Test
    void firstEverRunWithNoCheckpointStartsFromBlockZero() {
        when(checkpoints.findById((short) 1)).thenReturn(java.util.Optional.empty());
        source.enqueue(FakeLedgerEventSource.Connection.clean());

        listener.runOnce();

        assertThat(source.requestedFrom).containsExactly(Checkpoint.NONE);
    }

    @Test
    void aCleanRunProcessesEveryEventAndReturnsTrue() {
        LedgerEvidenceEvent e1 = event("tx-1", 1);
        LedgerEvidenceEvent e2 = event("tx-2", 2);
        stubHistory(e1);
        stubHistory(e2);
        source.enqueue(FakeLedgerEventSource.Connection.clean(e1, e2));
        when(processor.apply(any(), any())).thenReturn(true);

        boolean ok = listener.runOnce();

        assertThat(ok).isTrue();
        verify(processor, times(2)).apply(any(), any());
        assertThat(listener.hasEverConnected()).isTrue();
        assertThat(listener.consecutiveFailures()).isZero();
    }

    @Test
    void tier1SucceedingOnItsSecondAttemptNeverTearsDownTheStream() {
        LedgerEvidenceEvent e = event("tx-1", 1);
        stubHistory(e);
        source.enqueue(FakeLedgerEventSource.Connection.clean(e));
        when(processor.apply(any(), any()))
                .thenThrow(new RuntimeException("transient DB blip"))
                .thenReturn(true);

        boolean ok = listener.runOnce();

        assertThat(ok).isTrue();                          // the stream itself never failed
        assertThat(source.requestedFrom).hasSize(1);        // exactly one connection: no reconnect happened
        verify(processor, times(2)).apply(any(), any());   // local retry called it twice for the SAME event
    }

    @Test
    void tier1ExhaustedEscalatesToAStreamLevelFailureAfterExactlyThreeLocalAttempts() {
        LedgerEvidenceEvent e = event("tx-1", 1);
        stubHistory(e);
        source.enqueue(FakeLedgerEventSource.Connection.clean(e));
        when(processor.apply(any(), any())).thenThrow(new RuntimeException("still failing"));

        boolean ok = listener.runOnce();

        assertThat(ok).isFalse();                                      // escalated: this pass counts as a failure
        verify(processor, times(EventSyncListener.LOCAL_RETRY_ATTEMPTS)).apply(any(), any());
        assertThat(listener.consecutiveFailures()).isEqualTo(1);        // ONE stream-level failure, not 3
    }

    @Test
    void aStreamThatFailsPartwayCountsAsOneFailureAndTheNextRunOnceReconnectsFromWherever() {
        when(checkpoints.findById((short) 1)).thenReturn(java.util.Optional.empty());
        LedgerEvidenceEvent e = event("tx-1", 1);
        stubHistory(e);
        source.enqueue(FakeLedgerEventSource.Connection.failingAfter(new RuntimeException("stream dropped"), e));
        when(processor.apply(any(), any())).thenReturn(true);

        boolean ok = listener.runOnce();

        assertThat(ok).isFalse();
        assertThat(listener.consecutiveFailures()).isEqualTo(1);
        assertThat(listener.hasEverConnected()).as("it DID connect and process one event before failing").isTrue();
    }

    @Test
    void aFailureToEvenOpenTheStreamLeavesHasEverConnectedFalseUntilOneSucceeds() {
        source.enqueue(FakeLedgerEventSource.Connection.failingToOpen(new RuntimeException("peer unreachable")));
        source.enqueue(FakeLedgerEventSource.Connection.failingToOpen(new RuntimeException("peer unreachable")));
        source.enqueue(FakeLedgerEventSource.Connection.clean());

        assertThat(listener.runOnce()).isFalse();
        assertThat(listener.hasEverConnected()).as("never connected yet: still UNKNOWN, not merely DOWN").isFalse();
        assertThat(listener.consecutiveFailures()).isEqualTo(1);

        assertThat(listener.runOnce()).isFalse();
        assertThat(listener.hasEverConnected()).isFalse();
        assertThat(listener.consecutiveFailures()).isEqualTo(2);

        assertThat(listener.runOnce()).isTrue();               // third attempt succeeds
        assertThat(listener.hasEverConnected()).isTrue();
        assertThat(listener.consecutiveFailures()).as("a clean connection resets the counter").isZero();
    }

    @Test
    void fiveConsecutiveStreamLevelFailuresIsWhatTheHealthIndicatorTreatsAsDown() {
        for (int i = 0; i < EventSyncListener.UNHEALTHY_AFTER_CONSECUTIVE_FAILURES; i++) {
            source.enqueue(FakeLedgerEventSource.Connection.failingToOpen(new RuntimeException("still down")));
        }

        for (int i = 0; i < EventSyncListener.UNHEALTHY_AFTER_CONSECUTIVE_FAILURES; i++) {
            listener.runOnce();
        }

        assertThat(listener.consecutiveFailures()).isEqualTo(EventSyncListener.UNHEALTHY_AFTER_CONSECUTIVE_FAILURES);
        // the health indicator's own mapping of this count to DOWN is EventSyncHealthIndicatorTest's job
    }

    @Test
    void backoffDelayIsExponentialWithA30SecondCapAndJitter() {
        assertDelayNear(EventSyncListener.backoffDelay(1), 1000);
        assertDelayNear(EventSyncListener.backoffDelay(2), 2000);
        assertDelayNear(EventSyncListener.backoffDelay(3), 4000);
        assertDelayNear(EventSyncListener.backoffDelay(4), 8000);
        assertDelayNear(EventSyncListener.backoffDelay(5), 16000);
        assertDelayNear(EventSyncListener.backoffDelay(6), 30000);   // would be 32000 uncapped; capped at 30000
        assertDelayNear(EventSyncListener.backoffDelay(50), 30000);  // stays capped, never grows unbounded
        assertThat(EventSyncListener.backoffDelay(0)).isZero();
    }

    private static void assertDelayNear(long actual, long expected) {
        assertThat((double) actual).isCloseTo(expected, org.assertj.core.data.Percentage.withPercentage(20.5));
    }
}
