package com.blockevidence.backend.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * JWT settings (A1). The secret has no default on purpose (constraint C-07): with the env var unset
 * it binds to "", @NotBlank fails, and the application refuses to start instead of falling back to
 * a built-in key. 32 characters is the floor because HS256 needs a 256-bit key.
 */
@Validated
@ConfigurationProperties("blockevidence.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters (HS256 needs a 256-bit key)") String secret,
        @NotNull @DefaultValue("15m") Duration accessTtl,
        @NotNull @DefaultValue("7d") Duration refreshTtl,
        @NotBlank @DefaultValue("blockevidence-backend") String issuer) {

    // A record's generated toString would print the secret into any log line that dumps the properties.
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>, accessTtl=" + accessTtl + ", refreshTtl=" + refreshTtl
                + ", issuer=" + issuer + "]";
    }
}
