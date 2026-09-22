package com.blockevidence.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/** E1: a field left null is unchanged; at least one must be given. The case number and status cannot be changed here. */
public record UpdateCaseRequest(
        @Size(min = 1, max = 200) String title,
        @Size(max = 2000) String description,
        UUID leadOfficerId) {

    @AssertTrue(message = "at least one of title, description or leadOfficerId must be provided")
    public boolean isAtLeastOneChange() {
        return title != null || description != null || leadOfficerId != null;
    }
}
