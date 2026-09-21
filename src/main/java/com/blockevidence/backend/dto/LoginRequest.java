package com.blockevidence.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        // Upper bound only: BCrypt ignores input past 72 bytes, and an unbounded field is a cheap DoS vector.
        @NotBlank @Size(max = 128) String password) {
}
