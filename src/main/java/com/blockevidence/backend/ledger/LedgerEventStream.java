package com.blockevidence.backend.ledger;

import java.util.Iterator;

/**
 * G3: a live, blocking stream of chaincode events (design section 3), open until {@link #close()}. {@code next()}
 * blocks until an event is available or the underlying connection fails, in which case it throws - the caller
 * (the {@code sync} package's listener loop) is responsible for reconnection (design sections 6, 9), not this type.
 */
public interface LedgerEventStream extends Iterator<LedgerEvidenceEvent>, AutoCloseable {

    @Override
    void close();
}
