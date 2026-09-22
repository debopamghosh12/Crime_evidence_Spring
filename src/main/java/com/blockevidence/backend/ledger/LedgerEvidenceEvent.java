package com.blockevidence.backend.ledger;

/**
 * G3: one chaincode event, translated from the wire (design section 3/4). The chaincode payload carries
 * identifiers only (design section 4, C-06) - no actor, reason or case - so a consumer must enrich this via
 * {@link LedgerService#getHistory} matched by {@code txId} before it is useful for a read model.
 */
public record LedgerEvidenceEvent(String evidenceId, int version, String txId, long blockNumber, LedgerAction action) {
}
