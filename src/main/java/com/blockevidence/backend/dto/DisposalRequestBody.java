package com.blockevidence.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** B5: asks for disposal. Nothing is removed; a reason is mandatory. */
public record DisposalRequestBody(
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank @Size(max = 1000) String reason) {
}
