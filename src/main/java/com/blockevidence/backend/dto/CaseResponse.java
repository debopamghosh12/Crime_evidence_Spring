package com.blockevidence.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.domain.CaseRole;
import com.blockevidence.backend.domain.CaseStatus;

/**
 * A case with its team. Users appear by id only. {@code evidenceIds} is E3: the ledger evidence ids linked to
 * this case (off-chain index, {@code case_evidence}); {@code GET /api/cases/{id}/evidence} returns the full
 * records for these ids.
 */
public record CaseResponse(UUID id, String caseNumber, String title, String description, UUID leadOfficerId,
        CaseStatus status, UUID createdBy, Instant createdAt, List<Member> members, List<String> evidenceIds) {

    public record Member(UUID userId, CaseRole caseRole, UUID addedBy, Instant addedAt) {
    }
}
