package com.blockevidence.backend.dto;

/** {@code expiresIn} is the access token's lifetime in seconds, as in OAuth2 token responses. */
public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
