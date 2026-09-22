package com.blockevidence.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.crypto.Cipher;

import com.blockevidence.backend.support.FakeContentKeys;
import org.junit.jupiter.api.Test;

/** F2/F3 section 3 tier 2: RSA-2048 keypair provisioning and readback. */
class UserKeyServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    final UserKeyService service = FakeContentKeys.userKeyService(clock);

    @Test
    void provisioningTwiceKeepsTheFirstKeypair() {
        UUID userId = UUID.randomUUID();

        service.provision(userId);
        PublicKey first = service.publicKey(userId);
        service.provision(userId);                                            // e.g. the seeder running again
        PublicKey second = service.publicKey(userId);

        assertThat(second).as("re-provisioning must not orphan the first keypair").isEqualTo(first);
    }

    @Test
    void aProvisionedUsersPublicKeyEncryptsWhatTheirPrivateKeyDecrypts() throws Exception {
        UUID userId = UUID.randomUUID();
        service.provision(userId);

        PublicKey publicKey = service.publicKey(userId);
        PrivateKey privateKey = service.privateKey(userId);

        byte[] plaintext = "a raw AES content key would go here".getBytes(StandardCharsets.UTF_8);
        Cipher encrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        encrypt.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] wrapped = encrypt.doFinal(plaintext);

        Cipher decrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        decrypt.init(Cipher.DECRYPT_MODE, privateKey);
        assertThat(decrypt.doFinal(wrapped)).isEqualTo(plaintext);
    }

    @Test
    void aNeverProvisionedUserHasNoKeypair() {
        UUID userId = UUID.randomUUID();

        assertThat(service.hasKeyPair(userId)).isFalse();
        assertThatThrownBy(() -> service.publicKey(userId)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.privateKey(userId)).isInstanceOf(IllegalStateException.class);
    }
}
