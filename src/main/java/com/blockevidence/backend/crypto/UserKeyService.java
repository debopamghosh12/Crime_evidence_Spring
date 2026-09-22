package com.blockevidence.backend.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F2/F3 section 3 tier 2: provisions and reads back each user's RSA-2048 keypair. Called once per user (today,
 * from {@code DevUserSeeder}; A4 would call {@link #provision} the same way when it exists) and then only ever
 * READ from - there is no key-rotation path yet (design section 8, a stated limit).
 */
@Service
public class UserKeyService {

    private static final String RSA = "RSA";
    private static final int KEY_SIZE_BITS = 2048;

    private final UserKeyPairRepository repository;
    private final MasterKey masterKey;
    private final Clock clock;

    public UserKeyService(UserKeyPairRepository repository, MasterKey masterKey, Clock clock) {
        this.repository = repository;
        this.masterKey = masterKey;
        this.clock = clock;
    }

    /** Idempotent: a user who already has a keypair is left untouched, so re-running the seeder never
     *  regenerates (and thereby orphans) an existing user's key. */
    @Transactional
    public void provision(UUID userId) {
        if (repository.existsById(userId)) {
            return;
        }
        KeyPair pair = generate();
        byte[] encryptedPrivateKey = AesGcmCodec.encrypt(masterKey.bytes(), pair.getPrivate().getEncoded());
        repository.save(new UserKeyPair(userId, pair.getPublic().getEncoded(), encryptedPrivateKey, clock.instant()));
    }

    @Transactional(readOnly = true)
    public PublicKey publicKey(UUID userId) {
        UserKeyPair row = require(userId);
        try {
            return KeyFactory.getInstance(RSA).generatePublic(new X509EncodedKeySpec(row.getPublicKey()));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("stored public key for user " + userId + " is unreadable", e);
        }
    }

    /** Decrypts on demand every call - the plaintext private key is never cached in a field (section 1: the
     *  backend custodies it, but only for as long as one wrap/unwrap operation needs it). */
    @Transactional(readOnly = true)
    public PrivateKey privateKey(UUID userId) {
        UserKeyPair row = require(userId);
        byte[] pkcs8 = AesGcmCodec.decrypt(masterKey.bytes(), row.getEncryptedPrivateKey());
        try {
            return KeyFactory.getInstance(RSA).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("stored private key for user " + userId + " is unreadable", e);
        }
    }

    @Transactional(readOnly = true)
    public boolean hasKeyPair(UUID userId) {
        return repository.existsById(userId);
    }

    private UserKeyPair require(UUID userId) {
        return repository.findById(userId).orElseThrow(() ->
                new IllegalStateException("user " + userId + " has no provisioned keypair"));
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA);
            generator.initialize(KEY_SIZE_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available", e);
        }
    }
}
