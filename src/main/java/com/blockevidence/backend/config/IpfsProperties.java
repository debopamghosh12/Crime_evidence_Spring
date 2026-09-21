package com.blockevidence.backend.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * IPFS node settings (F1).
 *
 * <p>{@code timeout} bounds the health probe, so a dead node makes /actuator/health answer DOWN
 * quickly. {@code transferTimeout} bounds uploads and downloads of real content and must be far longer.
 * {@code lookupTimeout} is how long the node itself may search for content it does not hold; on a
 * private or offline node it is irrelevant (the node answers "not found" at once), but on a node that
 * can reach other peers a missing CID would otherwise make the request hang.
 */
@Validated
@ConfigurationProperties("blockevidence.ipfs")
public record IpfsProperties(
        @NotBlank @DefaultValue("http://localhost:5001") String apiUrl,
        @NotNull @DefaultValue("3s") Duration timeout,
        @NotNull @DefaultValue("60s") Duration transferTimeout,
        @NotNull @DefaultValue("10s") Duration lookupTimeout) {
}
