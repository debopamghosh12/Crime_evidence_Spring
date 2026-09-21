package com.blockevidence.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * B5: approve or reject a pending disposal. {@code expectedVersion} is the version the reviewer looked
 * at, so a record edited after the request cannot be approved unseen.
 */
public record DisposalDecisionBody(
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank @Size(max = 1000) String note) {
}
