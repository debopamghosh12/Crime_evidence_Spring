package com.blockevidence.backend.ledger;

import java.time.Instant;

/**
 * One committed version of a record, as returned by the ledger's history (C3): the transaction that
 * wrote it, that transaction's ledger timestamp, and the full record as of that write.
 */
public record LedgerHistoryEntry(String txId, Instant timestamp, LedgerEvidenceRecord record) {
}
