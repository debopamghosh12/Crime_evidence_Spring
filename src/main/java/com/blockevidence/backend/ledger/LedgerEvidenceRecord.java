package com.blockevidence.backend.ledger;

import java.time.Instant;

/**
 * Current on-ledger state of one evidence item. Provisional shape (see LedgerService). Deliberately
 * contains no personal data (C-06); {@code currentCustodian} is a user id, not a name.
 * {@code updatedAt} is the ledger transaction timestamp, not the server clock (C4).
 */
public record LedgerEvidenceRecord(String evidenceId, String caseId, String cid, String sha256, String status,
        int version, String currentCustodian, Instant updatedAt) {
}
