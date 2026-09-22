package com.blockevidence.backend.sync;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.blockevidence.backend.ledger.Checkpoint;
import com.blockevidence.backend.ledger.LedgerEventSource;
import com.blockevidence.backend.ledger.LedgerEventStream;
import com.blockevidence.backend.ledger.LedgerEvidenceEvent;
import com.blockevidence.backend.ledger.LedgerEvidenceRecord;
import com.blockevidence.backend.ledger.LedgerHistoryEntry;
import com.blockevidence.backend.ledger.LedgerService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * G3 (design docs/G3_SYNC_DESIGN.md sections 6, 9): the background listener loop. Started on a single-thread
 * executor after the application is up ({@link #run}); a crash, a peer restart, an orderer outage or a container
 * recreate all end up back at the top of {@link #loop} the same way, which re-reads the checkpoint from Postgres
 * and reconnects - nothing is ever silently dropped (design section 1). Two retry tiers (design section 9):
 * {@link #applyWithLocalRetry} rides out a brief Postgres blip WITHOUT tearing down the Fabric stream; anything
 * that reaches {@link #loop}'s catch block tears the stream down and backs off exponentially before reconnecting.
 *
 * <p>Not active under {@code memory-ledger} (no {@link LedgerEventSource} bean exists there, mirroring A2's
 * {@code IdentityStore}/{@code WalletHealthIndicator} scoping): the reference ledger has no block history.
 */
@Component
@Profile("!memory-ledger")
public class EventSyncListener implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventSyncListener.class);

    static final int LOCAL_RETRY_ATTEMPTS = 3;
    static final long LOCAL_RETRY_DELAY_MS = 200;
    static final long BACKOFF_BASE_MS = 1000;
    static final long BACKOFF_CAP_MS = 30_000;
    static final int UNHEALTHY_AFTER_CONSECUTIVE_FAILURES = 5;

    private final LedgerEventSource eventSource;
    private final LedgerService ledger;
    private final EventProcessor processor;
    private final LedgerSyncCheckpointRepository checkpoints;
    private final Clock clock;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(daemonThreadFactory());
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean everConnected = false;
    private volatile boolean running = true;
    private volatile LedgerEventStream currentStream;
    private volatile Instant lastActivityAt;

    public EventSyncListener(LedgerEventSource eventSource, LedgerService ledger, EventProcessor processor,
            LedgerSyncCheckpointRepository checkpoints, Clock clock) {
        this.eventSource = eventSource;
        this.ledger = ledger;
        this.processor = processor;
        this.checkpoints = checkpoints;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        executor.submit(this::loop);
    }

    private void loop() {
        while (running) {
            if (!runOnce()) {
                sleep(backoffDelay(consecutiveFailures.get()));
            }
        }
        log.info("Event sync listener stopped");
    }

    /**
     * One connect-and-consume pass: open a stream from the current checkpoint, consume events until the stream
     * ends or fails. Package-private and side-effect-observable (via {@link #consecutiveFailures()}/
     * {@link #hasEverConnected()}) so it can be driven directly, once per call, in tests without a background
     * thread (design section 10's "sequence of failures and a recovery" scenarios).
     *
     * @return true if the stream opened and ran to a clean end (or was stopped by {@link #shutdown()}); false if
     *         it failed (the caller, {@link #loop}, is then responsible for the backoff delay - kept out of this
     *         method so a test can assert on the failure without actually sleeping).
     */
    boolean runOnce() {
        Checkpoint checkpoint = readCheckpoint();
        try (LedgerEventStream stream = eventSource.openEventStream(checkpoint)) {
            currentStream = stream;
            onStreamOpened();
            while (running && stream.hasNext()) {
                LedgerEvidenceEvent event = stream.next();
                processEvent(event);
            }
            return true;
        } catch (RuntimeException e) {
            if (!running) {
                return true; // our own close() during shutdown surfaces as an exception here; not a real failure
            }
            onStreamFailed(e);
            return false;
        } finally {
            currentStream = null;
        }
    }

    /** Enrichment (a ledger read) happens OUTSIDE any DB transaction, then tier-1 local retry (design section 9). */
    private void processEvent(LedgerEvidenceEvent event) {
        LedgerEvidenceRecord record = findEnrichedRecord(event);
        boolean applied = applyWithLocalRetry(event, record);
        lastActivityAt = clock.instant();
        log.debug("event {} txId={} version={} action={} -> {}", event.evidenceId(), event.txId(), event.version(),
                event.action(), applied ? "applied" : "already processed (redelivery)");
    }

    private LedgerEvidenceRecord findEnrichedRecord(LedgerEvidenceEvent event) {
        List<LedgerHistoryEntry> history = ledger.getHistory(event.evidenceId());
        return history.stream().filter(h -> h.txId().equals(event.txId())).map(LedgerHistoryEntry::record)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "txId " + event.txId() + " for " + event.evidenceId() + " not found in its own history"));
    }

    /** Tier 1 (design section 9): up to LOCAL_RETRY_ATTEMPTS, a short FIXED pause, the Fabric stream untouched. */
    private boolean applyWithLocalRetry(LedgerEvidenceEvent event, LedgerEvidenceRecord record) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= LOCAL_RETRY_ATTEMPTS; attempt++) {
            try {
                return processor.apply(event, record);
            } catch (RuntimeException e) {
                last = e;
                log.warn("local retry {}/{} for txId={} failed: {}", attempt, LOCAL_RETRY_ATTEMPTS, event.txId(),
                        e.toString());
                if (attempt < LOCAL_RETRY_ATTEMPTS) {
                    sleep(LOCAL_RETRY_DELAY_MS);
                }
            }
        }
        throw last; // tier 1 exhausted: escalate to the stream-level failure path (design section 9)
    }

    private Checkpoint readCheckpoint() {
        LedgerSyncCheckpoint row = checkpoints.findById((short) 1).orElse(null);
        if (row == null || row.getBlockNumber() == null) {
            return Checkpoint.NONE;
        }
        return new Checkpoint(row.getBlockNumber(), row.getTxId());
    }

    // ------------------------------------------------------------------------- health (design section 9)

    private void onStreamOpened() {
        consecutiveFailures.set(0);
        everConnected = true;
        lastActivityAt = clock.instant();
    }

    private void onStreamFailed(Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("event sync stream failed ({} consecutive): {}", failures, e.toString());
    }

    /** Read by {@link EventSyncHealthIndicator}. */
    boolean hasEverConnected() {
        return everConnected;
    }

    int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    Optional<Instant> lastActivityAt() {
        return Optional.ofNullable(lastActivityAt);
    }

    static long backoffDelay(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return 0;
        }
        long exponential = BACKOFF_BASE_MS * (1L << Math.min(consecutiveFailures - 1, 20));
        long capped = Math.min(exponential, BACKOFF_CAP_MS);
        double jitterFactor = 0.8 + Math.random() * 0.4; // +/-20%
        return Math.round(capped * jitterFactor);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "event-sync-listener");
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        running = false;
        LedgerEventStream stream = currentStream;
        if (stream != null) {
            stream.close(); // unblocks a pending stream.next()/hasNext() so the loop can exit cleanly
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
