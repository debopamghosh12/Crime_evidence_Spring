package com.blockevidence.backend.domain;

/**
 * Result of comparing stored content with the hash recorded on the ledger (C2).
 * NOT_CHECKED is what GET /api/evidence/{id} reports unless the caller asks for verification, because
 * verifying means downloading and re-hashing the whole file.
 */
public enum VerificationStatus {
    VERIFIED,
    TAMPERED,
    NOT_FOUND,
    NOT_CHECKED
}
