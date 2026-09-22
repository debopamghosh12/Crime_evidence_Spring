package com.blockevidence.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * E1. {@code caseNumber} uses the exact character set the ledger accepts for an evidence caseId, so a case number can always be used
 * as one (E3). {@code leadOfficerId} names the lead officer, not the acting user (the creator comes from the JWT, C-05).
 */
public record CreateCaseRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._/-]{1,64}$",
                message = "must be 1-64 characters: letters, digits, . _ / -") String caseNumber,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @NotNull UUID leadOfficerId) {
}
