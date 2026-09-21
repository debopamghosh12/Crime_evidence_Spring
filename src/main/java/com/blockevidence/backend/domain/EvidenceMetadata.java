package com.blockevidence.backend.domain;

import java.time.Instant;

/**
 * The metadata document stored on IPFS (B1). The ledger keeps only its CID and SHA-256, so this
 * document is where descriptive detail lives. Each update (B4) writes a NEW document and points
 * {@code previousMetadataCid} at the one it replaces, so old versions are never overwritten.
 *
 * <p>Caution: IPFS content is not private. Anything here is readable by anyone who obtains the CID
 * until envelope encryption (F2) exists, so descriptions should not contain personal data that must be
 * protected. {@code collectorId} is a user id from the JWT, never a value supplied in the request (C-05).
 */
public record EvidenceMetadata(
        int schemaVersion,
        String evidenceId,
        String caseId,
        EvidenceType evidenceType,
        String description,
        String location,
        String notes,
        Instant collectedAt,
        String collectorId,
        FileInfo file,
        int metadataVersion,
        String previousMetadataCid) {

    public static final int SCHEMA_VERSION = 1;

    /** Facts about the uploaded file as seen at registration. {@code sha256} equals the ledger's fileSha256. */
    public record FileInfo(String originalName, String contentType, long size, String sha256) {
    }
}
