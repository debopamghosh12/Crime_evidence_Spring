package com.blockevidence.backend.dto;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceMetadata;
import com.blockevidence.backend.domain.EvidenceStatus;
import com.blockevidence.backend.domain.EvidenceType;

/**
 * B3: an evidence item as the API shows it. The top-level fields are the LEDGER's view (the source of
 * truth for integrity). {@code metadata} is the descriptive document fetched from IPFS; it is null with
 * {@code metadataAvailable=false} when IPFS cannot supply it, so ledger information is never lost because
 * storage is down (F1). {@code verification} is NOT_CHECKED unless the caller asked for it.
 */
public record EvidenceResponse(
        String evidenceId,
        String caseId,
        EvidenceType evidenceType,
        EvidenceStatus status,
        int version,
        String currentCustodian,
        String metadataCid,
        String metadataSha256,
        String fileCid,
        String fileSha256,
        Long fileSize,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        String lastAction,
        String lastReason,
        Disposal disposal,
        boolean metadataAvailable,
        EvidenceMetadata metadata,
        VerificationResponse verification) {

    /** {@code state} is NONE or PENDING; the other fields are set only while PENDING. */
    public record Disposal(String state, String requestedBy, Instant requestedAt, String reason) {
    }
}
