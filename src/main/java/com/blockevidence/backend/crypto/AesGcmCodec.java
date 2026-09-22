package com.blockevidence.backend.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * F2 (docs/F2_F3_ENVELOPE_ENCRYPTION_DESIGN.md section 7): AES-256-GCM, stateless. Every method here works on
 * whatever key it is given - the evidence content key (ECK) for file/metadata bytes, or the master key for a
 * user's stored private key (section 3 tier 3) - the algorithm does not care which tier a key belongs to.
 *
 * <p>Wire format is always {@code IV (12 bytes) || ciphertext || GCM tag (16 bytes)}, IV first because it is not
 * secret and the reader needs it before it can start decrypting. A fresh random IV is generated for every
 * encryption call; reusing a (key, IV) pair with GCM is a real key-recovery break, never reuse one deliberately.
 */
public final class AesGcmCodec {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    static final int TAG_LENGTH_BITS = 128;
    /** IV + GCM tag: the fixed overhead every ciphertext blob carries over its plaintext length. */
    public static final int OVERHEAD_BYTES = IV_LENGTH + (TAG_LENGTH_BITS / 8);

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcmCodec() {
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        byte[] iv = randomIv();
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, iv);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /** @throws IllegalArgumentException the GCM tag does not verify (tampered or wrong key) - a caller-facing
     *          integrity failure, not a programming error, so callers should catch this specifically. */
    public static byte[] decrypt(byte[] key, byte[] ivAndCiphertext) {
        if (ivAndCiphertext.length < IV_LENGTH) {
            throw new IllegalArgumentException("ciphertext shorter than the IV (" + ivAndCiphertext.length + " bytes)");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(ivAndCiphertext, 0, iv, 0, IV_LENGTH);
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, iv);
            return cipher.doFinal(ivAndCiphertext, IV_LENGTH, ivAndCiphertext.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES-GCM decryption failed (wrong key or tampered ciphertext)", e);
        }
    }

    /**
     * Wraps a plaintext stream so the IV is yielded first, then the ciphertext, as it is consumed - the whole
     * point of {@link CipherInputStream} is that encryption happens while the bytes flow to IPFS (C1's existing
     * streaming property, preserved: {@code HashingInputStream} still wraps this once more upstream in
     * {@code EvidenceService}, so nothing is buffered in memory).
     */
    public static InputStream encryptingStream(byte[] key, InputStream plaintext) {
        byte[] iv = randomIv();
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, iv);
            return new SequenceInputStream(new ByteArrayInputStream(iv), new CipherInputStream(plaintext, cipher));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Reads the 12-byte IV off the front of {@code ivAndCiphertext} (a blocking read - the caller's stream must
     * actually have at least 12 bytes) and returns a stream that decrypts everything after it. The GCM tag is
     * checked by {@link CipherInputStream} only once the stream is read to its end; a caller that does not read
     * to the end never learns of a tampered ciphertext this way, so callers wanting that guarantee must read
     * fully (as {@code EvidenceService}'s file endpoint does).
     */
    public static InputStream decryptingStream(byte[] key, InputStream ivAndCiphertext) {
        byte[] iv = new byte[IV_LENGTH];
        try {
            int read = ivAndCiphertext.readNBytes(iv, 0, IV_LENGTH);
            if (read != IV_LENGTH) {
                throw new IllegalArgumentException("ciphertext shorter than the IV (" + read + " bytes)");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, iv);
            return new CipherInputStream(ivAndCiphertext, cipher);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher;
    }

    private static byte[] randomIv() {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        return iv;
    }
}
