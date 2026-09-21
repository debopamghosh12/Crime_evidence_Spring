package com.blockevidence.backend.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * B4: a versioned metadata update. {@code reason} is mandatory. {@code expectedVersion} is the version
 * the caller last read; a stale value is rejected (409) instead of silently overwriting a newer change.
 * A field left null is unchanged; sending none of them is rejected. The evidence file, type and case
 * cannot be changed here.
 */
public record UpdateEvidenceRequest(
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank @Size(max = 1000) String reason,
        @Size(min = 1, max = 2000) String description,
        @Size(max = 500) String location,
        @Size(max = 2000) String notes) {

    @AssertTrue(message = "at least one of description, location or notes must be provided")
    public boolean isAtLeastOneChange() {
        return description != null || location != null || notes != null;
    }
}
