package com.blockevidence.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.blockevidence.backend.domain.CaseRole;
import com.blockevidence.backend.domain.CaseStatus;

/** A case with its team. Users appear by id only. */
public record CaseResponse(UUID id, String caseNumber, String title, String description, UUID leadOfficerId,
        CaseStatus status, UUID createdBy, Instant createdAt, List<Member> members) {

    public record Member(UUID userId, CaseRole caseRole, UUID addedBy, Instant addedAt) {
    }
}
