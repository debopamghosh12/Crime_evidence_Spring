package com.blockevidence.backend.ledger;

import java.time.Instant;

/** Outcome of a committed ledger write: the transaction id and the ledger's own timestamp (C4). */
public record LedgerTxResult(String txId, Instant timestamp) {
}
