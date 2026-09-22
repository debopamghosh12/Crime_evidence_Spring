package com.blockevidence.backend.sync;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.blockevidence.backend.ledger.Checkpoint;
import com.blockevidence.backend.ledger.LedgerEventSource;
import com.blockevidence.backend.ledger.LedgerEventStream;
import com.blockevidence.backend.ledger.LedgerEvidenceEvent;

/**
 * A scripted {@link LedgerEventSource} for {@link EventSyncListenerTest}: each call to {@link #openEventStream}
 * pops one pre-programmed "connection" off a queue, so a test can script exactly what happens across several
 * {@code runOnce()} calls (a clean run, a run that fails partway, a run where opening itself fails).
 */
class FakeLedgerEventSource implements LedgerEventSource {

    /** One scripted connection: some events, then optionally a failure instead of a clean end. */
    static final class Connection {
        final List<LedgerEvidenceEvent> events;
        final RuntimeException failAfter; // thrown after the events are exhausted, instead of ending cleanly
        final RuntimeException failToOpen; // thrown by openEventStream itself, before any event

        private Connection(List<LedgerEvidenceEvent> events, RuntimeException failAfter, RuntimeException failToOpen) {
            this.events = events;
            this.failAfter = failAfter;
            this.failToOpen = failToOpen;
        }

        static Connection clean(LedgerEvidenceEvent... events) {
            return new Connection(List.of(events), null, null);
        }

        static Connection failingAfter(RuntimeException ex, LedgerEvidenceEvent... events) {
            return new Connection(List.of(events), ex, null);
        }

        static Connection failingToOpen(RuntimeException ex) {
            return new Connection(List.of(), null, ex);
        }
    }

    final Deque<Connection> script = new ArrayDeque<>();
    final List<Checkpoint> requestedFrom = new ArrayList<>();

    void enqueue(Connection c) {
        script.add(c);
    }

    @Override
    public LedgerEventStream openEventStream(Checkpoint from) {
        requestedFrom.add(from);
        if (script.isEmpty()) {
            // an idle connection: opens fine, has nothing to deliver (like a real stream with no new events yet)
            return new FiniteStream(List.of(), null);
        }
        Connection c = script.poll();
        if (c.failToOpen != null) {
            throw c.failToOpen;
        }
        return new FiniteStream(c.events, c.failAfter);
    }

    private static final class FiniteStream implements LedgerEventStream {
        private final Deque<LedgerEvidenceEvent> remaining;
        private final RuntimeException failAfter;
        boolean closed;

        FiniteStream(List<LedgerEvidenceEvent> events, RuntimeException failAfter) {
            this.remaining = new ArrayDeque<>(events);
            this.failAfter = failAfter;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                throw new IllegalStateException("closed"); // matches a real stream: closing unblocks/fails it
            }
            if (remaining.isEmpty() && failAfter != null) {
                throw failAfter;
            }
            return !remaining.isEmpty();
        }

        @Override
        public LedgerEvidenceEvent next() {
            return remaining.poll();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
