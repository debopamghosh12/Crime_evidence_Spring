package com.blockevidence.backend.ledger;

import com.blockevidence.backend.exception.FeatureNotImplementedException;

/** Thrown by every FabricLedgerService operation until the real implementation lands (Phase 2/3). */
public class LedgerNotImplementedException extends FeatureNotImplementedException {

    public LedgerNotImplementedException(String operation) {
        super("Ledger operation '" + operation + "' is not implemented yet");
    }
}
