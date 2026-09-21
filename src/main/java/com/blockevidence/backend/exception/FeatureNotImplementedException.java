package com.blockevidence.backend.exception;

/**
 * Base for the Phase 1 stubs (FabricLedgerService, HttpIpfsClient pin/fetch/unpin). Lives here so
 * GlobalExceptionHandler can map every stub to 501 without importing ledger/ or storage/ types.
 */
public class FeatureNotImplementedException extends RuntimeException {

    public FeatureNotImplementedException(String message) {
        super(message);
    }
}
