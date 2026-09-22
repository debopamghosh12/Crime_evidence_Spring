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
 * <p>{@code certPath}/{@code keyPath} are the SERVICE identity, used for every read and, before A2, for every write
 * too. Since A2 (design section 3.3), writes are signed with the ACTING USER's own identity, loaded from
 * {@code walletDir} by {@link com.blockevidence.backend.ledger.FileWalletIdentityStore}; the service identity is
 * kept only for reads (which need no per-user role, C-08 does not apply to them) and the health probe.
 * {@code walletPassphrase} decrypts a wallet key only if it is PKCS#8-encrypted (A2-Q5); an unencrypted wallet key
 * needs no passphrase. Neither wallet setting is required for the app to start: an unconfigured wallet means every
 * write gets 403 LEDGER_IDENTITY_MISSING, exactly like a user with no enrolled identity.
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
        /** A2: directory holding one subdirectory per user id, each with cert.pem/key.pem (outside the repo, C-07). */
        String walletDir,
        /** A2: passphrase for an encrypted (PKCS#8) wallet private key; blank means wallet keys are unencrypted. */
        String walletPassphrase,
        @NotNull @DefaultValue("5s") Duration evaluateTimeout,
        @NotNull @DefaultValue("15s") Duration endorseTimeout,
        @NotNull @DefaultValue("15s") Duration submitTimeout,
        @NotNull @DefaultValue("60s") Duration commitTimeout) {

    /** True when the three SERVICE identity paths are all set (reads, and the health probe). */
    public boolean isConfigured() {
        return notBlank(tlsCertPath) && notBlank(certPath) && notBlank(keyPath);
    }

    /** True when a wallet directory is configured at all (individual users may still have no entry in it). */
    public boolean isWalletConfigured() {
        return notBlank(walletDir);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
