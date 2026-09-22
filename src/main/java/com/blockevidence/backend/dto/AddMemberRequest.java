package com.blockevidence.backend.dto;

import java.util.UUID;

import com.blockevidence.backend.domain.CaseRole;
import jakarta.validation.constraints.NotNull;

/** E2: add a team member with a role on the case. LEAD_OFFICER is assigned through the case's lead officer, not here. */
public record AddMemberRequest(@NotNull UUID userId, @NotNull CaseRole caseRole) {
}
