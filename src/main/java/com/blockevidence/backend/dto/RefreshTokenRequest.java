package com.blockevidence.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of both /api/auth/refresh and /api/auth/logout: each presents the refresh token. */
public record RefreshTokenRequest(@NotBlank @Size(max = 512) String refreshToken) {
}
