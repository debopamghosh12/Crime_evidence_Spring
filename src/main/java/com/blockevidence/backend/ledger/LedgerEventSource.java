package com.blockevidence.backend.ledger;

/**
 * G3: the seam for consuming chaincode events (design docs/G3_SYNC_DESIGN.md section 3), separate from
 * {@link LedgerService} because nothing else needs it. Only {@code ledger/} may import Fabric types (C-01); the
 * {@code sync} package that consumes this never does.
 */
public interface LedgerEventSource {

    /**
     * Opens a new stream from the given position ({@link Checkpoint#NONE} replays all history from block 0).
     * Blocking to open is fine (mirrors the underlying SDK call); reconnection/backoff on a later failure is the
     * CALLER's job (design section 9) - this method itself is called once per connection attempt.
     */
    LedgerEventStream openEventStream(Checkpoint from);
}
