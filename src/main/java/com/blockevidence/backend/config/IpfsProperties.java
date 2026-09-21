package com.blockevidence.backend.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * IPFS node settings (F1). {@code timeout} bounds both connect and read so that a dead node makes
 * /actuator/health answer DOWN quickly instead of hanging the probe.
 */
@Validated
@ConfigurationProperties("blockevidence.ipfs")
public record IpfsProperties(
        @NotBlank @DefaultValue("http://localhost:5001") String apiUrl,
        @NotNull @DefaultValue("3s") Duration timeout) {
}
