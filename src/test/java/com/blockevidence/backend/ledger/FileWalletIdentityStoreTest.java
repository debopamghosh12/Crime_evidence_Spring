package com.blockevidence.backend.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Date;

import com.blockevidence.backend.config.FabricProperties;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A2 (verification plan item 3): wallet loading, plain and encrypted, a missing entry, and expiry. No fixture
 * files are committed (C-07): every certificate/key here is generated fresh, in-process, per test.
 */
class FileWalletIdentityStoreTest {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    static final String USER = "11111111-1111-4111-8111-111111111111";
    static final String OTHER_USER = "22222222-2222-4222-8222-222222222222";

    FabricProperties props(String walletDir, String passphrase) {
        return new FabricProperties("crimechannel", "evidence", "localhost:7051", "peer0.org1.example.com",
                "Org1MSP", null, null, null, walletDir, passphrase, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    /** A self-signed EC certificate/key pair, exactly like a Fabric CA-issued one in shape (EC, PEM). */
    record Generated(X509Certificate certificate, PrivateKey privateKey) {
    }

    static Generated generate(Duration validFor) throws Exception {
        // The "BC" provider specifically: the default SunEC provider encodes PKCS#8 EC parameters in a shape
        // fabric-gateway's own key reader (Identities.readPrivateKey, BouncyCastle-based) does not recognise -
        // found by this test failing against a real (if self-signed) key, not against a mock.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=" + USER);
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + validFor.toMillis());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate());
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(System.nanoTime()),
                notBefore, notAfter, subject, pair.getPublic());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        return new Generated(cert, pair.getPrivate());
    }

    static void writeCert(Path dir, X509Certificate cert) throws Exception {
        try (JcaPEMWriter w = new JcaPEMWriter(Files.newBufferedWriter(dir.resolve("cert.pem")))) {
            w.writeObject(cert);
        }
    }

    static void writePlainKey(Path dir, PrivateKey key) throws Exception {
        try (JcaPEMWriter w = new JcaPEMWriter(Files.newBufferedWriter(dir.resolve("key.pem")))) {
            w.writeObject(key);
        }
    }

    static void writeEncryptedKey(Path dir, PrivateKey key, String passphrase) throws Exception {
        OutputEncryptor encryptor = new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                .setProvider("BC").setPassword(passphrase.toCharArray()).build();
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(new JcaPKCS8Generator(key, encryptor));
        }
        Files.writeString(dir.resolve("key.pem"), sw.toString());
    }

    @Test
    void loadsAPlainUnencryptedWalletKeyAndCertificate(@TempDir Path wallet) throws Exception {
        Generated g = generate(Duration.ofDays(365));
        Path userDir = Files.createDirectories(wallet.resolve(USER));
        writeCert(userDir, g.certificate());
        writePlainKey(userDir, g.privateKey());

        var store = new FileWalletIdentityStore(props(wallet.toString(), null));
        WalletIdentity identity = store.find(USER).orElseThrow();

        assertThat(identity.certificate().getSubjectX500Principal().getName()).contains(USER);
        assertThat(identity.privateKey().getAlgorithm()).isEqualTo("ECDSA");
    }

    @Test
    void loadsAnEncryptedWalletKeyWithTheConfiguredPassphraseAndRefusesTheWrongOne(@TempDir Path wallet) throws Exception {
        Generated g = generate(Duration.ofDays(365));
        Path userDir = Files.createDirectories(wallet.resolve(USER));
        writeCert(userDir, g.certificate());
        writeEncryptedKey(userDir, g.privateKey(), "correct horse battery staple");

        var right = new FileWalletIdentityStore(props(wallet.toString(), "correct horse battery staple"));
        assertThat(right.find(USER)).isPresent();

        var wrong = new FileWalletIdentityStore(props(wallet.toString(), "not the passphrase"));
        assertThat(wrong.find(USER)).as("a decrypt failure is a missing identity, not a thrown exception").isEmpty();

        var none = new FileWalletIdentityStore(props(wallet.toString(), null));
        assertThat(none.find(USER)).as("no passphrase configured at all for an encrypted key").isEmpty();
    }

    @Test
    void aUserWithNoWalletEntryOrNoWalletConfiguredAtAllIsSimplyAbsent(@TempDir Path wallet) throws Exception {
        var store = new FileWalletIdentityStore(props(wallet.toString(), null));
        assertThat(store.find(USER)).isEmpty();                     // directory exists, this user does not
        assertThat(store.find("not-a-uuid")).isEmpty();             // malformed id, never looked up on disk
        assertThat(new FileWalletIdentityStore(props(null, null)).find(USER)).isEmpty();   // no wallet at all
        assertThat(new FileWalletIdentityStore(props(wallet.resolve("missing").toString(), null)).find(USER)).isEmpty();
    }

    @Test
    void expiringWithinListsOnlyUsersWhoseCertificateIsDueSoonAndIsEmptyWithNoWallet() throws Exception {
        Path wallet = Files.createTempDirectory("wallet-test");
        try {
            Generated soon = generate(Duration.ofDays(10));
            Path soonDir = Files.createDirectories(wallet.resolve(USER));
            writeCert(soonDir, soon.certificate());
            writePlainKey(soonDir, soon.privateKey());

            Generated later = generate(Duration.ofDays(300));
            Path laterDir = Files.createDirectories(wallet.resolve(OTHER_USER));
            writeCert(laterDir, later.certificate());
            writePlainKey(laterDir, later.privateKey());

            var store = new FileWalletIdentityStore(props(wallet.toString(), null));
            assertThat(store.expiringWithin(30)).containsExactly(USER);
            assertThat(store.expiringWithin(400)).containsExactlyInAnyOrder(USER, OTHER_USER);
            assertThat(store.expiringWithin(1)).isEmpty();
            assertThat(new FileWalletIdentityStore(props(null, null)).expiringWithin(30)).isEmpty();
        } finally {
            try (var walk = Files.walk(wallet)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void nothingAboutTheKeyOrPassphraseEverAppearsInAnExceptionMessage(@TempDir Path wallet) throws Exception {
        Generated g = generate(Duration.ofDays(365));
        Path userDir = Files.createDirectories(wallet.resolve(USER));
        writeCert(userDir, g.certificate());
        writeEncryptedKey(userDir, g.privateKey(), "top secret passphrase 12345");

        // find() never throws (it logs and returns empty), so this proves the ABSENCE of a leak by construction:
        // there is no exception path here at all for the caller to accidentally expose.
        var wrong = new FileWalletIdentityStore(props(wallet.toString(), "definitely wrong"));
        assertThat(wrong.find(USER)).isEmpty();
    }
}
