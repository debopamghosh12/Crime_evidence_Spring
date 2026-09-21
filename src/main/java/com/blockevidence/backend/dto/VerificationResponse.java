package com.blockevidence.backend.dto;

import java.time.Instant;

import com.blockevidence.backend.domain.VerificationStatus;

/**
 * C2 result. {@code status} is the overall verdict: TAMPERED if any artifact fails its hash, else
 * NOT_FOUND if any is missing, else VERIFIED. {@code file} is null for PHYSICAL evidence.
 * {@code checkedAt} is the server's time of the check, not evidence time. When {@code status} is
 * NOT_CHECKED (a plain GET) every other field except {@code evidenceId} is null.
 */
public record VerificationResponse(String evidenceId, VerificationStatus status, Integer ledgerVersion,
        Instant checkedAt, ComponentCheck file, ComponentCheck metadata) {

    public static VerificationResponse notChecked(String evidenceId) {
        return new VerificationResponse(evidenceId, VerificationStatus.NOT_CHECKED, null, null, null, null);
    }
}
