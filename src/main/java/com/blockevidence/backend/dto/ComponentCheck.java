package com.blockevidence.backend.dto;

import com.blockevidence.backend.domain.VerificationStatus;

/**
 * Outcome of checking one stored artifact (the file, or the metadata document) against the ledger.
 * {@code actualSha256} is null when the content could not be found.
 */
public record ComponentCheck(String cid, String expectedSha256, String actualSha256, VerificationStatus result) {
}
