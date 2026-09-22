package com.blockevidence.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** D2: accept, reject or cancel a pending transfer. The note is optional. */
public record TransferDecisionBody(
        @NotNull @Min(1) Integer expectedVersion,
        @Size(max = 1000) String note) {
}
