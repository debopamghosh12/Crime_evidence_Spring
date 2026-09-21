package com.blockevidence.backend.ledger;

import com.blockevidence.backend.domain.EvidenceType;

/**
 * Everything the ledger needs to create an evidence record (B1, C1). Only ids, hashes and CIDs
 * (C-06). {@code fileCid}, {@code fileSha256} and {@code fileSize} are null for PHYSICAL evidence and
 * all three are required for DIGITAL evidence.
 */
public record LedgerNewEvidence(
        String evidenceId,
        String caseId,
        EvidenceType evidenceType,
        String metadataCid,
        String metadataSha256,
        String fileCid,
        String fileSha256,
        Long fileSize) {
}
