package com.blockevidence.backend.crypto;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * F2/F3 section 3 tier 2: one user's RSA-2048 keypair. {@code publicKey} is X.509 SubjectPublicKeyInfo DER, not
 * sensitive. {@code encryptedPrivateKey} is PKCS8 DER encrypted with the master key (AesGcmCodec wire format,
 * IV || ciphertext || tag) - never held in plaintext outside a single {@link UserKeyService} call. One row per
 * user, {@code user_id} itself is the primary key (a user has exactly one active keypair; no rotation history
 * table exists yet, CONSTRAINTS.md C-10).
 */
@Entity
@Table(name = "user_keys")
public class UserKeyPair {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "encrypted_private_key", nullable = false)
    private byte[] encryptedPrivateKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserKeyPair() {
        // required by JPA
    }

    public UserKeyPair(UUID userId, byte[] publicKey, byte[] encryptedPrivateKey, Instant createdAt) {
        this.userId = userId;
        this.publicKey = publicKey;
        this.encryptedPrivateKey = encryptedPrivateKey;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public byte[] getEncryptedPrivateKey() {
        return encryptedPrivateKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
