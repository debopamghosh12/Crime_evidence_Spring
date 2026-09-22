package com.blockevidence.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * F2/F3 (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md). {@code masterKey} protects every user's stored private key
 * at rest (CONSTRAINTS.md C-10): it never enters the repository or a config default (C-07), the same rule
 * already applied to the JWT signing secret. No default value is supplied here on purpose - a blank value fails
 * {@code @NotBlank} and the application refuses to start, exactly like {@code JwtProperties.secret}, because
 * unlike the Fabric wallet (which is allowed to be "not configured" so the app runs without a ledger) every
 * evidence registration needs this key: there is no meaningful degraded mode for encryption.
 *
 * <p>{@code masterKey} is base64. {@link com.blockevidence.backend.crypto.MasterKey} decodes and validates it
 * is exactly 32 bytes (AES-256) at startup, failing fast on a misconfigured value rather than at the first
 * encrypt/decrypt call.
 */
@Validated
@ConfigurationProperties("blockevidence.encryption")
public record EncryptionProperties(@NotBlank String masterKey) {
}
