package com.blockevidence.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Fabric network settings (G1/K3). Defaults are the names used by the Node.js prototype. Identity
 * and gateway-connection settings arrive with the real FabricLedgerService in Phase 2/3.
 */
@Validated
@ConfigurationProperties("blockevidence.fabric")
public record FabricProperties(
        @NotBlank @DefaultValue("crimechannel") String channel,
        @NotBlank @DefaultValue("basic") String chaincode) {
}
