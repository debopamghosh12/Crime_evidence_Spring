package com.blockevidence.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * D2/D4: hand evidence to another user. {@code toUserId} names the RECEIVER, not the acting user: the sender is always the
 * authenticated principal (C-05). A reason is mandatory; notes are optional.
 */
public record TransferRequest(
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull UUID toUserId,
        @NotBlank @Size(max = 1000) String reason,
        @Size(max = 1000) String notes) {
}
