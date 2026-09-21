package com.blockevidence.backend.dto;

import java.time.Instant;

import com.blockevidence.backend.domain.EvidenceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * B1: the JSON part of the registration request. It deliberately has NO collector/officer field
 * (C-05): who registered the evidence is taken from the JWT. A client that sends one anyway has it
 * ignored, which a test asserts.
 */
public record RegisterEvidenceRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._/-]{1,64}$",
                message = "must be 1-64 characters: letters, digits, . _ / -") String caseId,
        @NotNull EvidenceType type,
        @NotBlank @Size(max = 2000) String description,
        @Size(max = 500) String location,
        @Past(message = "must be in the past") Instant collectedAt,
        @Size(max = 2000) String notes) {
}
