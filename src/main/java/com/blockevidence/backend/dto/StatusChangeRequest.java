package com.blockevidence.backend.dto;

import com.blockevidence.backend.domain.EvidenceStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** D1/D4: a status change needs a reason. {@code expectedVersion} is the version the caller last read. */
public record StatusChangeRequest(
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull EvidenceStatus status,
        @NotBlank @Size(max = 1000) String reason) {
}
