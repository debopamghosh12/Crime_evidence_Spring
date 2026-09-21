package com.blockevidence.backend.ledger;

/** What the last write to a record did. One value per chaincode write function. */
public enum LedgerAction {
    CREATED,
    METADATA_UPDATED,
    STATUS_CHANGED,
    DISPOSAL_REQUESTED,
    DISPOSAL_APPROVED,
    DISPOSAL_REJECTED
}
