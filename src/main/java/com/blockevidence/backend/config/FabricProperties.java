package com.blockevidence.backend.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Fabric network settings (G1/K3). Everything environment-specific comes from environment variables, and the
 * identity material is referenced by FILE PATH: the certificate and private key never enter the repository
 * or a config default (constraint C-07).
 *
 * <p>{@code certPath} and {@code keyPath} may each point at a file or at a directory holding exactly one file
 * (Fabric's MSP layout has {@code signcerts/cert.pem} and {@code keystore/<hash>_sk}). If any of the three
 * paths is blank the ledger is "not configured": the application still starts, ledger calls answer
 * 503 LEDGER_UNAVAILABLE, and health reports UNKNOWN. That is deliberate so the app runs without Fabric.
 *
 * <p>The single application identity here is what the chaincode sees for every user in Phase 2; the per-user
 * role is passed as an argument the chaincode cannot authenticate (constraint C-08, until A2).
 */
@Validated
@ConfigurationProperties("blockevidence.fabric")
public record FabricProperties(
        @NotBlank @DefaultValue("crimechannel") String channel,
        @NotBlank @DefaultValue("evidence") String chaincode,
        @NotBlank @DefaultValue("localhost:7051") String peerEndpoint,
        /** TLS server name to expect; the peer's certificate is issued for its Docker hostname, not "localhost". */
        @NotBlank @DefaultValue("peer0.org1.example.com") String peerHostAlias,
        @NotBlank @DefaultValue("Org1MSP") String mspId,
        String tlsCertPath,
        String certPath,
        String keyPath,
        @NotNull @DefaultValue("5s") Duration evaluateTimeout,
        @NotNull @DefaultValue("15s") Duration endorseTimeout,
        @NotNull @DefaultValue("15s") Duration submitTimeout,
        @NotNull @DefaultValue("60s") Duration commitTimeout) {

    /** True when the three identity paths are all set. */
    public boolean isConfigured() {
        return notBlank(tlsCertPath) && notBlank(certPath) && notBlank(keyPath);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
