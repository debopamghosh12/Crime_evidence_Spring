package com.blockevidence.backend.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

/** F2 (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md section 7): the stateless AES-256-GCM codec both the byte[]
 *  and streaming paths in EvidenceService are built on. */
class AesGcmCodecTest {

    static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void byteArrayRoundTripReturnsTheExactOriginalBytes() {
        byte[] key = key();
        byte[] plaintext = "the seized phone image, byte for byte".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = AesGcmCodec.encrypt(key, plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(ciphertext.length).isEqualTo(plaintext.length + AesGcmCodec.OVERHEAD_BYTES);
        assertThat(AesGcmCodec.decrypt(key, ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void twoEncryptionsOfTheSameBytesUnderTheSameKeyProduceDifferentCiphertext() {
        byte[] key = key();
        byte[] plaintext = "same content twice".getBytes(StandardCharsets.UTF_8);

        byte[] a = AesGcmCodec.encrypt(key, plaintext);
        byte[] b = AesGcmCodec.encrypt(key, plaintext);

        assertThat(a).as("a fresh random IV every call means the ciphertext differs even for identical plaintext")
                .isNotEqualTo(b);
        assertThat(AesGcmCodec.decrypt(key, a)).isEqualTo(plaintext);
        assertThat(AesGcmCodec.decrypt(key, b)).isEqualTo(plaintext);
    }

    @Test
    void decryptingWithTheWrongKeyFailsTheGcmTagCheck() {
        byte[] ciphertext = AesGcmCodec.encrypt(key(), "secret".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> AesGcmCodec.decrypt(key(), ciphertext)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptingTamperedCiphertextFailsTheGcmTagCheck() {
        byte[] key = key();
        byte[] ciphertext = AesGcmCodec.encrypt(key, "secret".getBytes(StandardCharsets.UTF_8));
        ciphertext[ciphertext.length - 1] ^= 0x01;                             // flip a bit in the tag/ciphertext

        assertThatThrownBy(() -> AesGcmCodec.decrypt(key, ciphertext)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamingRoundTripMatchesTheByteArrayPath() throws Exception {
        byte[] key = key();
        byte[] plaintext = "streamed exactly like C1's HashingInputStream chains it".getBytes(StandardCharsets.UTF_8);

        InputStream encrypted = AesGcmCodec.encryptingStream(key, new ByteArrayInputStream(plaintext));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        encrypted.transferTo(buffer);
        byte[] ivAndCiphertext = buffer.toByteArray();

        assertThat(ivAndCiphertext.length).isEqualTo(plaintext.length + AesGcmCodec.OVERHEAD_BYTES);
        assertThat(AesGcmCodec.decrypt(key, ivAndCiphertext)).isEqualTo(plaintext);

        InputStream decrypted = AesGcmCodec.decryptingStream(key, new ByteArrayInputStream(ivAndCiphertext));
        assertThat(decrypted.readAllBytes()).isEqualTo(plaintext);
    }

    @Test
    void ciphertextShorterThanTheIvIsRejected() {
        assertThatThrownBy(() -> AesGcmCodec.decrypt(key(), new byte[5])).isInstanceOf(IllegalArgumentException.class);
    }
}
