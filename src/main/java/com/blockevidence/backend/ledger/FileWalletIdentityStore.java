package com.blockevidence.backend.ledger;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.Security;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.blockevidence.backend.config.FabricProperties;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.hyperledger.fabric.client.identity.Identities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A2, Option 1 (design section 3.4): a wallet directory maintained by the operator's enrollment script
 * (scripts/fabric/enroll_users.sh), never written to by the running application. Layout: one subdirectory per
 * user id, each holding {@code cert.pem} and {@code key.pem}. A key whose PEM header says
 * {@code ENCRYPTED PRIVATE KEY} is decrypted with {@link FabricProperties#walletPassphrase()} (BouncyCastle,
 * C-04/D-047); anything else is read the same way {@code FabricLedgerService} already reads the service key.
 *
 * <p>Nothing here is a startup dependency: a missing directory, a missing user, or a corrupt entry all resolve
 * to {@link Optional#empty()} rather than throwing, so a wallet problem surfaces as 403 LEDGER_IDENTITY_MISSING
 * on the one write that needed it, not as an application failure.
 */
@Component
@Profile("!memory-ledger")
public class FileWalletIdentityStore implements IdentityStore {

    static {
        // Needed to decrypt a wallet key's PBES2 (PKCS#8) scheme: found live (docs/bugs/wallet-key-decrypt-needs-bc-provider.md)
        // - without the "BC" JCE provider registered, decryption fails with "Cannot find any provider supporting
        // AES/CBC/PKCS7Padding" even though bcpkix-jdk18on is on the classpath, because the algorithm lookup goes
        // through java.security.Security's provider list, not the classpath.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(FileWalletIdentityStore.class);
    // Same shape the backend already accepts as a user id elsewhere (UUID); the wallet directory name IS the id.
    private static final Pattern USER_ID = Pattern.compile("^[0-9a-fA-F-]{36}$");

    private final FabricProperties properties;

    public FileWalletIdentityStore(FabricProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<WalletIdentity> find(String userId) {
        if (!properties.isWalletConfigured() || userId == null || !USER_ID.matcher(userId).matches()) {
            return Optional.empty();
        }
        Path dir = Path.of(properties.walletDir()).resolve(userId);
        Path certFile = dir.resolve("cert.pem");
        Path keyFile = dir.resolve("key.pem");
        if (!Files.isRegularFile(certFile) || !Files.isRegularFile(keyFile)) {
            return Optional.empty();
        }
        try {
            X509Certificate certificate = readCertificate(certFile);
            PrivateKey privateKey = readPrivateKey(keyFile, properties.walletPassphrase());
            return Optional.of(new WalletIdentity(certificate, privateKey));
        } catch (IOException | CertificateException | InvalidKeyException | RuntimeException e) {
            // Deliberately not logging file contents or the passphrase; the exception type is enough to diagnose.
            log.warn("Cannot load wallet identity for a user: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public List<String> expiringWithin(int days) {
        List<String> soon = new ArrayList<>();
        if (!properties.isWalletConfigured()) {
            return soon;
        }
        Path root = Path.of(properties.walletDir());
        if (!Files.isDirectory(root)) {
            return soon;
        }
        Instant cutoff = Instant.now().plus(days, ChronoUnit.DAYS);
        try (var entries = Files.list(root)) {
            for (Path dir : entries.filter(Files::isDirectory).toList()) {
                String userId = dir.getFileName().toString();
                find(userId).ifPresent(identity -> {
                    if (identity.certificate().getNotAfter().toInstant().isBefore(cutoff)) {
                        soon.add(userId);
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Cannot scan the wallet directory for expiring identities: {}", e.getClass().getSimpleName());
        }
        return soon;
    }

    private static X509Certificate readCertificate(Path file) throws IOException, CertificateException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Identities.readX509Certificate(reader);
        }
    }

    /** Unencrypted PEM is read the same way FabricLedgerService reads the service key; an "ENCRYPTED PRIVATE
     *  KEY" (PKCS#8) header is decrypted first (A2-Q5). */
    static PrivateKey readPrivateKey(Path file, String passphrase) throws IOException, InvalidKeyException {
        String pem = Files.readString(file, StandardCharsets.UTF_8);
        if (!pem.contains("ENCRYPTED PRIVATE KEY")) {
            try (Reader reader = new StringReader(pem)) {
                return Identities.readPrivateKey(reader);
            }
        }
        if (passphrase == null || passphrase.isBlank()) {
            throw new IOException("wallet key is encrypted but no passphrase is configured (FABRIC_WALLET_PASSPHRASE)");
        }
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object parsed = parser.readObject();
            if (!(parsed instanceof PKCS8EncryptedPrivateKeyInfo encrypted)) {
                throw new IOException("expected an encrypted PKCS#8 private key");
            }
            InputDecryptorProvider decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider("BC")
                    .build(passphrase.toCharArray());
            PrivateKeyInfo info = encrypted.decryptPrivateKeyInfo(decryptor);
            return new JcaPEMKeyConverter().setProvider("BC").getPrivateKey(info);
        } catch (OperatorCreationException | PKCSException e) {
            throw new IOException("cannot decrypt the wallet key", e);
        }
    }
}
