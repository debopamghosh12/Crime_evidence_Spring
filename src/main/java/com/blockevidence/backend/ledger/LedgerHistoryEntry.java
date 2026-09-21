package com.blockevidence.backend.ledger;

import java.time.Instant;

/** One version of an evidence record as returned by GetHistory (C3). Provisional shape. */
public record LedgerHistoryEntry(String txId, Instant timestamp, int version, String status, String cid,
        String sha256, String actorId, String reason) {
}
